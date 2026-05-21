<?php
require_once 'config.php';
if (!isset($_SESSION['admin_id'])) { header('Location: login.php'); exit; }
$id = intval($_GET['id'] ?? 0);
if ($id > 0) {
    $pdo = new PDO("mysql:host=" . DB_HOST . ";dbname=" . DB_NAME . ";charset=utf8mb4", DB_USER, DB_PASS);
    $row = $pdo->prepare("SELECT file_path FROM media_captures WHERE id=?");
    $row->execute([$id]);
    $r = $row->fetch(PDO::FETCH_ASSOC);
    if ($r && file_exists($r['file_path'])) unlink($r['file_path']);
    $pdo->prepare("DELETE FROM media_captures WHERE id=?")->execute([$id]);
}
header('Location: index.php');
exit;
