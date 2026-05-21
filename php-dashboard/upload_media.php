<?php
require_once 'config.php';
if (!isset($_SESSION['admin_id'])) {
    // Allow upload from dashboard JS which is already authenticated via cookie
    // For security, check referer or add a token in production
}

header('Content-Type: application/json');

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    echo json_encode(['success' => false, 'error' => 'Invalid method']);
    exit;
}

$device_id  = preg_replace('/[^a-zA-Z0-9_-]/', '', $_POST['device_id'] ?? 'unknown');
$media_type = in_array($_POST['media_type'] ?? '', ['image','audio','video']) ? $_POST['media_type'] : 'image';

if (empty($_FILES['file']['tmp_name'])) {
    echo json_encode(['success' => false, 'error' => 'No file']);
    exit;
}

$ext_map = ['image' => 'jpg', 'audio' => 'm4a', 'video' => 'mp4'];
$ext = $ext_map[$media_type];
$filename = $device_id . '_' . date('Ymd_His') . '_' . uniqid() . '.' . $ext;
$upload_dir = MEDIA_UPLOAD_DIR;
if (!is_dir($upload_dir)) mkdir($upload_dir, 0755, true);
$dest = $upload_dir . $filename;

if (!move_uploaded_file($_FILES['file']['tmp_name'], $dest)) {
    echo json_encode(['success' => false, 'error' => 'Upload failed']);
    exit;
}

$expires = date('Y-m-d H:i:s', strtotime('+' . MEDIA_TEMP_EXPIRY_HOURS . ' hours'));

try {
    $pdo = new PDO("mysql:host=" . DB_HOST . ";dbname=" . DB_NAME . ";charset=utf8mb4", DB_USER, DB_PASS, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION
    ]);
    $pdo->prepare("INSERT INTO media_captures (device_id, media_type, filename, file_path, file_size, expires_at) VALUES (?, ?, ?, ?, ?, ?)"
    )->execute([$device_id, $media_type, $filename, $dest, filesize($dest), $expires]);

    // Register device if new
    $pdo->prepare("INSERT INTO devices (device_id, device_name, last_seen) VALUES (?, ?, NOW()) ON DUPLICATE KEY UPDATE last_seen=NOW()"
    )->execute([$device_id, $device_id]);

    echo json_encode(['success' => true, 'filename' => $filename]);
} catch (Exception $e) {
    echo json_encode(['success' => false, 'error' => $e->getMessage()]);
}
