#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use tauri::{Manager, State, Window};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::Mutex;

#[derive(Debug, Clone, Serialize, Deserialize)]
struct CameraInfo {
    name: String,
    ip: String,
    port: u16,
}

#[derive(Debug, Clone, Serialize)]
struct PreviewFrame {
    camera_ip: String,
    frame_data: String, // base64 encoded
}

#[derive(Debug, Clone, Serialize)]
struct PhotoSaved {
    camera_ip: String,
    path: String,
}

type CameraConnections = Arc<Mutex<HashMap<String, Arc<Mutex<Option<TcpStream>>>>>>;

#[tauri::command]
async fn capture_photo(
    camera_ip: String,
    connections: State<'_, CameraConnections>,
) -> Result<String, String> {
    let conns = connections.lock().await;
    
    if let Some(conn_mutex) = conns.get(&camera_ip) {
        let mut conn_guard = conn_mutex.lock().await;
        if let Some(stream) = conn_guard.as_mut() {
            stream
                .write_all(b"CMD_TAKE_PHOTO\n")
                .await
                .map_err(|e| format!("Failed to send command: {}", e))?;
            
            return Ok("Photo capture command sent".to_string());
        }
    }
    
    Err("Camera not connected".to_string())
}

#[tauri::command]
async fn start_preview(
    camera_ip: String,
    connections: State<'_, CameraConnections>,
) -> Result<String, String> {
    let conns = connections.lock().await;
    
    if let Some(conn_mutex) = conns.get(&camera_ip) {
        let mut conn_guard = conn_mutex.lock().await;
        if let Some(stream) = conn_guard.as_mut() {
            stream
                .write_all(b"CMD_START_PREVIEW\n")
                .await
                .map_err(|e| format!("Failed to send command: {}", e))?;
            
            return Ok("Preview started".to_string());
        }
    }
    
    Err("Camera not connected".to_string())
}

#[tauri::command]
async fn stop_preview(
    camera_ip: String,
    connections: State<'_, CameraConnections>,
) -> Result<String, String> {
    let conns = connections.lock().await;
    
    if let Some(conn_mutex) = conns.get(&camera_ip) {
        let mut conn_guard = conn_mutex.lock().await;
        if let Some(stream) = conn_guard.as_mut() {
            stream
                .write_all(b"CMD_STOP_PREVIEW\n")
                .await
                .map_err(|e| format!("Failed to send command: {}", e))?;
            
            return Ok("Preview stopped".to_string());
        }
    }
    
    Err("Camera not connected".to_string())
}

async fn service_discovery_loop(
    window: Window,
    connections: CameraConnections,
) {
    use zeroconf::{MdnsBrowser, ServiceDiscovery, ServiceType};
    
    let service_type = ServiceType::new("mycamapp", "tcp").unwrap();
    let mut browser = MdnsBrowser::new(service_type);
    
    let window_clone = window.clone();
    let connections_clone = connections.clone();
    
    browser.set_service_discovered_callback(Box::new(move |result, _context| {
        let service = result.unwrap();
        let ip = service
            .address()
            .map(|addr| addr.to_string())
            .unwrap_or_else(|| "unknown".to_string());
        let port = service.port();
        
        println!("Discovered camera at {}:{}", ip, port);
        
        let camera_info = CameraInfo {
            name: service.name().to_string(),
            ip: ip.clone(),
            port,
        };
        
        window_clone
            .emit("camera_discovered", &camera_info)
            .unwrap();
        
        // Spawn task to connect to camera
        let window_for_task = window_clone.clone();
        let connections_for_task = connections_clone.clone();
        let ip_for_task = ip.clone();
        
        tokio::spawn(async move {
            handle_camera(ip_for_task, port, window_for_task, connections_for_task).await;
        });
    }));
    
    browser.browse_services().unwrap();
    
    // Keep the browser alive
    loop {
        tokio::time::sleep(tokio::time::Duration::from_secs(1)).await;
    }
}

async fn handle_camera(
    ip: String,
    port: u16,
    window: Window,
    connections: CameraConnections,
) {
    let addr: SocketAddr = format!("{}:{}", ip, port)
        .parse()
        .expect("Invalid address");
    
    match TcpStream::connect(addr).await {
        Ok(stream) => {
            println!("Connected to camera at {}", ip);
            
            // Store the connection
            {
                let mut conns = connections.lock().await;
                conns.insert(ip.clone(), Arc::new(Mutex::new(Some(stream))));
            }
            
            // Start reading loop
            loop {
                let conns = connections.lock().await;
                if let Some(conn_mutex) = conns.get(&ip) {
                    let mut conn_guard = conn_mutex.lock().await;
                    if let Some(stream) = conn_guard.as_mut() {
                        // Read frame header: [type:1byte][size:4bytes]
                        let mut header = [0u8; 5];
                        match stream.read_exact(&mut header).await {
                            Ok(_) => {
                                let frame_type = header[0] as char;
                                let size = u32::from_be_bytes([
                                    header[1], header[2], header[3], header[4],
                                ]) as usize;
                                
                                // Read frame data
                                let mut data = vec![0u8; size];
                                match stream.read_exact(&mut data).await {
                                    Ok(_) => {
                                        match frame_type {
                                            'P' => {
                                                // Preview frame
                                                let base64_data = base64::encode(&data);
                                                let frame = PreviewFrame {
                                                    camera_ip: ip.clone(),
                                                    frame_data: base64_data,
                                                };
                                                window.emit("preview_frame", &frame).ok();
                                            }
                                            'H' => {
                                                // High-res photo
                                                let home_dir = dirs::home_dir()
                                                    .unwrap_or_else(|| std::path::PathBuf::from("."));
                                                let pictures_dir = home_dir.join("Pictures");
                                                tokio::fs::create_dir_all(&pictures_dir).await.ok();
                                                
                                                let timestamp = chrono::Local::now()
                                                    .format("%Y%m%d_%H%M%S");
                                                let filename = format!("photo_{}.jpg", timestamp);
                                                let path = pictures_dir.join(&filename);
                                                
                                                if let Ok(_) = tokio::fs::write(&path, &data).await {
                                                    let photo_saved = PhotoSaved {
                                                        camera_ip: ip.clone(),
                                                        path: path.to_string_lossy().to_string(),
                                                    };
                                                    window.emit("photo_saved", &photo_saved).ok();
                                                    println!("Photo saved to {:?}", path);
                                                }
                                            }
                                            _ => {
                                                println!("Unknown frame type: {}", frame_type);
                                            }
                                        }
                                    }
                                    Err(e) => {
                                        println!("Error reading frame data: {}", e);
                                        break;
                                    }
                                }
                            }
                            Err(e) => {
                                println!("Error reading frame header: {}", e);
                                break;
                            }
                        }
                    }
                }
            }
            
            // Clean up connection
            let mut conns = connections.lock().await;
            conns.remove(&ip);
        }
        Err(e) => {
            println!("Failed to connect to camera at {}: {}", ip, e);
        }
    }
}

fn main() {
    let connections: CameraConnections = Arc::new(Mutex::new(HashMap::new()));
    
    tauri::Builder::default()
        .manage(connections.clone())
        .setup(|app| {
            let window = app.get_window("main").unwrap();
            let connections_clone = connections.clone();
            
            // Spawn service discovery task
            tokio::spawn(async move {
                service_discovery_loop(window, connections_clone).await;
            });
            
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            capture_photo,
            start_preview,
            stop_preview
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
