<?php
// FIX Bug 12 — Added authentication check (session OR upload token)
session_start();
require_once 'config.php';

// Allow access if logged in via session OR valid upload token header
$uploadToken = defined('UPLOAD_TOKEN') ? UPLOAD_TOKEN : '';
$requestToken = isset($_SERVER['HTTP_X_UPLOAD_TOKEN']) ? $_SERVER['HTTP_X_UPLOAD_TOKEN'] : '';

$isLoggedIn = isset($_SESSION['admin_id']);
$hasValidToken = !empty($uploadToken) && hash_equals($uploadToken, $requestToken);

if (!$isLoggedIn && !$hasValidToken) {
    http_response_code(403);
    die(json_encode(['success' => false, 'error' => 'Unauthorized']));
}

header('Content-Type: application/json');

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    die(json_encode(['success' => false, 'error' => 'Method not allowed']));
}

// Sanitize device_id
$device_id = preg_replace('/[^a-zA-Z0-9_\-]/', '', $_POST['device_id'] ?? '');
if (empty($device_id)) {
    die(json_encode(['success' => false, 'error' => 'Invalid device_id']));
}

// Whitelist media_type
$allowed_types = ['image', 'audio', 'video'];
$media_type = $_POST['media_type'] ?? '';
if (!in_array($media_type, $allowed_types)) {
    die(json_encode(['success' => false, 'error' => 'Invalid media_type']));
}

// Validate file upload
if (!isset($_FILES['file']) || $_FILES['file']['error'] !== UPLOAD_ERR_OK) {
    die(json_encode(['success' => false, 'error' => 'File upload error']));
}

// Max 100MB
if ($_FILES['file']['size'] > 104857600) {
    die(json_encode(['success' => false, 'error' => 'File too large (max 100MB)']));
}

// Create uploads directory if needed
$uploads_dir = __DIR__ . '/uploads/';
if (!is_dir($uploads_dir)) {
    mkdir($uploads_dir, 0755, true);
    file_put_contents($uploads_dir . '.htaccess', "Options -Indexes\nphp_flag engine off\n");
}

// Generate safe filename
$ext_map = ['image' => 'jpg', 'audio' => 'm4a', 'video' => 'mp4'];
$ext = $ext_map[$media_type];
$filename = $device_id . '_' . date('Ymd_His') . '_' . uniqid() . '.' . $ext;
$file_path = $uploads_dir . $filename;

if (!move_uploaded_file($_FILES['file']['tmp_name'], $file_path)) {
    die(json_encode(['success' => false, 'error' => 'Failed to save file']));
}

$file_size = filesize($file_path);

try {
    $pdo = new PDO(
        'mysql:host=' . DB_HOST . ';dbname=' . DB_NAME . ';charset=utf8mb4',
        DB_USER, DB_PASS,
        [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]
    );

    // Insert media record
    $expiry_hours = defined('MEDIA_TEMP_EXPIRY_HOURS') ? (int)MEDIA_TEMP_EXPIRY_HOURS : 24;
    $stmt = $pdo->prepare(
        'INSERT INTO media_captures (device_id, media_type, filename, file_path, file_size, expires_at)
         VALUES (?, ?, ?, ?, ?, DATE_ADD(NOW(), INTERVAL ? HOUR))'
    );
    $stmt->execute([$device_id, $media_type, $filename, 'uploads/' . $filename, $file_size, $expiry_hours]);

    // Upsert device last_seen
    $stmt2 = $pdo->prepare(
        'INSERT INTO devices (device_id, last_seen) VALUES (?, NOW())
         ON DUPLICATE KEY UPDATE last_seen = NOW()'
    );
    $stmt2->execute([$device_id]);

    echo json_encode(['success' => true, 'filename' => $filename]);
} catch (PDOException $e) {
    // Remove uploaded file on DB error
    if (file_exists($file_path)) unlink($file_path);
    http_response_code(500);
    die(json_encode(['success' => false, 'error' => 'Database error']));
}
