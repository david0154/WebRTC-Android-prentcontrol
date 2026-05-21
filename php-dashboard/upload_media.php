<?php
/**
 * FIX Bug 10 — upload_media.php had NO authentication check.
 * Any external request could upload arbitrary files.
 * Fix: enforce admin session at the top.
 */
session_start();

if (!isset($_SESSION['admin_id'])) {
    http_response_code(403);
    echo json_encode(['success' => false, 'error' => 'Unauthorized']);
    exit;
}

require_once __DIR__ . '/config.php';

header('Content-Type: application/json');
header('X-Content-Type-Options: nosniff');

// -------------------------------------------------------------------------
// Input sanitization
// -------------------------------------------------------------------------
$deviceId  = preg_replace('/[^a-zA-Z0-9_\-]/', '', $_POST['device_id']  ?? '');
$mediaType = $_POST['media_type'] ?? '';

if (empty($deviceId)) {
    http_response_code(400);
    echo json_encode(['success' => false, 'error' => 'Missing device_id']);
    exit;
}

$allowedTypes = ['image', 'audio', 'video'];
if (!in_array($mediaType, $allowedTypes, true)) {
    http_response_code(400);
    echo json_encode(['success' => false, 'error' => 'Invalid media_type']);
    exit;
}

// -------------------------------------------------------------------------
// File validation
// -------------------------------------------------------------------------
if (!isset($_FILES['file']) || $_FILES['file']['error'] !== UPLOAD_ERR_OK) {
    $err = $_FILES['file']['error'] ?? 'no file';
    http_response_code(400);
    echo json_encode(['success' => false, 'error' => 'File upload error: ' . $err]);
    exit;
}

$allowedMimes = [
    'image' => ['image/jpeg', 'image/png', 'image/webp'],
    'audio' => ['audio/mp4', 'audio/mpeg', 'audio/ogg', 'audio/wav'],
    'video' => ['video/mp4', 'video/webm', 'video/ogg'],
];

$finfo    = new finfo(FILEINFO_MIME_TYPE);
$detectedMime = $finfo->file($_FILES['file']['tmp_name']);
if (!in_array($detectedMime, $allowedMimes[$mediaType], true)) {
    http_response_code(400);
    echo json_encode(['success' => false, 'error' => 'MIME type mismatch: ' . $detectedMime]);
    exit;
}

$maxBytes = 50 * 1024 * 1024; // 50 MB
if ($_FILES['file']['size'] > $maxBytes) {
    http_response_code(413);
    echo json_encode(['success' => false, 'error' => 'File too large (max 50MB)']);
    exit;
}

// -------------------------------------------------------------------------
// Store file
// -------------------------------------------------------------------------
$extMap   = ['image/jpeg'=>'jpg','image/png'=>'png','image/webp'=>'webp',
             'audio/mp4'=>'m4a','audio/mpeg'=>'mp3','audio/ogg'=>'ogg','audio/wav'=>'wav',
             'video/mp4'=>'mp4','video/webm'=>'webm','video/ogg'=>'ogv'];
$ext      = $extMap[$detectedMime] ?? 'bin';
$filename = $deviceId . '_' . date('Ymd_His') . '_' . uniqid() . '.' . $ext;
$uploadDir = __DIR__ . '/uploads/';

if (!is_dir($uploadDir)) {
    mkdir($uploadDir, 0755, true);
}

$destPath = $uploadDir . $filename;
if (!move_uploaded_file($_FILES['file']['tmp_name'], $destPath)) {
    http_response_code(500);
    echo json_encode(['success' => false, 'error' => 'Failed to save file']);
    exit;
}

// -------------------------------------------------------------------------
// DB insert
// -------------------------------------------------------------------------
try {
    $pdo = new PDO(
        'mysql:host=' . DB_HOST . ';dbname=' . DB_NAME . ';charset=utf8mb4',
        DB_USER, DB_PASS,
        [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]
    );

    $expiryHours = defined('MEDIA_TEMP_EXPIRY_HOURS') ? (int)MEDIA_TEMP_EXPIRY_HOURS : 24;

    $stmt = $pdo->prepare(
        'INSERT INTO media_captures (device_id, media_type, filename, file_path, file_size, expires_at)
         VALUES (?, ?, ?, ?, ?, DATE_ADD(NOW(), INTERVAL ? HOUR))'
    );
    $stmt->execute([$deviceId, $mediaType, $filename,
                    'uploads/' . $filename,
                    (int)$_FILES['file']['size'],
                    $expiryHours]);

    // Upsert device presence
    $stmt2 = $pdo->prepare(
        'INSERT INTO devices (device_id, last_seen)
         VALUES (?, NOW())
         ON DUPLICATE KEY UPDATE last_seen = NOW()'
    );
    $stmt2->execute([$deviceId]);

    echo json_encode(['success' => true, 'filename' => $filename]);

} catch (PDOException $e) {
    http_response_code(500);
    echo json_encode(['success' => false, 'error' => 'DB error: ' . $e->getMessage()]);
    // Clean up uploaded file on DB failure
    @unlink($destPath);
}
