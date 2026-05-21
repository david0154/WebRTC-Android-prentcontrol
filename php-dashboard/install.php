<?php
/**
 * One-Click Installer
 * ⚠️ DELETE THIS FILE immediately after successful installation!
 */
$error = '';
$success = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $db_host   = trim($_POST['db_host'] ?? 'localhost');
    $db_name   = trim($_POST['db_name'] ?? '');
    $db_user   = trim($_POST['db_user'] ?? '');
    $db_pass   = $_POST['db_pass'] ?? '';
    $admin_user = trim($_POST['admin_user'] ?? '');
    $admin_pass = $_POST['admin_pass'] ?? '';
    $node_url  = rtrim(trim($_POST['node_url'] ?? ''), '/');
    $upload_token = bin2hex(random_bytes(24)); // auto-generated secure token

    if (empty($db_name) || empty($db_user) || empty($admin_user) || empty($admin_pass) || empty($node_url)) {
        $error = 'All fields are required.';
    } else {
        try {
            $pdo = new PDO("mysql:host=$db_host;charset=utf8mb4", $db_user, $db_pass,
                [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);

            $pdo->exec("CREATE DATABASE IF NOT EXISTS `" . str_replace('`','', $db_name) . "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            $pdo->exec("USE `" . str_replace('`','', $db_name) . "`");

            $pdo->exec("CREATE TABLE IF NOT EXISTS admins (
                id         INT AUTO_INCREMENT PRIMARY KEY,
                username   VARCHAR(100) NOT NULL UNIQUE,
                password   VARCHAR(255) NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB");

            $pdo->exec("CREATE TABLE IF NOT EXISTS devices (
                id          INT AUTO_INCREMENT PRIMARY KEY,
                device_id   VARCHAR(100) NOT NULL UNIQUE,
                device_name VARCHAR(200),
                last_seen   DATETIME,
                created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_device_id (device_id)
            ) ENGINE=InnoDB");

            $pdo->exec("CREATE TABLE IF NOT EXISTS media_captures (
                id          INT AUTO_INCREMENT PRIMARY KEY,
                device_id   VARCHAR(100) NOT NULL,
                media_type  ENUM('image','audio','video') NOT NULL,
                filename    VARCHAR(300) NOT NULL,
                file_path   VARCHAR(500) NOT NULL,
                file_size   INT DEFAULT 0,
                captured_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                expires_at  DATETIME DEFAULT NULL,
                INDEX idx_device (device_id),
                INDEX idx_type   (media_type),
                INDEX idx_time   (captured_at)
            ) ENGINE=InnoDB");

            // Login attempts table for brute-force protection (FIX — missing feature)
            $pdo->exec("CREATE TABLE IF NOT EXISTS login_attempts (
                id         INT AUTO_INCREMENT PRIMARY KEY,
                ip_address VARCHAR(45) NOT NULL,
                attempted_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_ip (ip_address),
                INDEX idx_time (attempted_at)
            ) ENGINE=InnoDB");

            $hashed = password_hash($admin_pass, PASSWORD_BCRYPT, ['cost' => 12]);
            $stmt = $pdo->prepare("INSERT IGNORE INTO admins (username, password) VALUES (?, ?)");
            $stmt->execute([$admin_user, $hashed]);

            // Write config.php with upload token
            $config = "<?php\n";
            $config .= "define('DB_HOST', " . var_export($db_host, true) . ");\n";
            $config .= "define('DB_NAME', " . var_export($db_name, true) . ");\n";
            $config .= "define('DB_USER', " . var_export($db_user, true) . ");\n";
            $config .= "define('DB_PASS', " . var_export($db_pass, true) . ");\n";
            $config .= "define('NODE_BACKEND_URL', " . var_export($node_url, true) . ");\n";
            $config .= "define('MEDIA_TEMP_EXPIRY_HOURS', 24);\n";
            $config .= "define('UPLOAD_TOKEN', " . var_export($upload_token, true) . ");\n";
            $config .= "define('SESSION_TIMEOUT', 3600);\n";

            file_put_contents(__DIR__ . '/config.php', $config);

            // Create uploads directory
            $uploads = __DIR__ . '/uploads';
            if (!is_dir($uploads)) mkdir($uploads, 0755, true);
            file_put_contents($uploads . '/.htaccess', "Options -Indexes\nphp_flag engine off\n");

            $success = "Installation successful! Your upload token (save this): <code>$upload_token</code><br><br>"
                     . "<strong style='color:red'>⚠️ DELETE this install.php file NOW via FTP!</strong><br><br>"
                     . '<a href="login.php">→ Go to Dashboard Login</a>';
        } catch (PDOException $e) {
            $error = 'Database error: ' . htmlspecialchars($e->getMessage());
        }
    }
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Install — WebRTC ParentControl</title>
<style>
  body { font-family: Arial, sans-serif; max-width: 600px; margin: 40px auto; padding: 20px; background: #1a1a2e; color: #eee; }
  h1 { color: #e94560; }
  input { width: 100%; padding: 10px; margin: 6px 0 14px; box-sizing: border-box; border-radius: 6px; border: 1px solid #444; background: #16213e; color: #eee; }
  button { background: #e94560; color: #fff; border: none; padding: 12px 30px; border-radius: 6px; cursor: pointer; font-size: 15px; }
  .error { background: #c0392b; padding: 12px; border-radius: 6px; margin-bottom: 16px; }
  .success { background: #27ae60; padding: 12px; border-radius: 6px; margin-bottom: 16px; }
  label { font-size: 13px; color: #aaa; }
</style>
</head>
<body>
<h1>🛠️ WebRTC ParentControl — Installer</h1>
<?php if ($error): ?><div class="error"><?= htmlspecialchars($error) ?></div><?php endif; ?>
<?php if ($success): ?><div class="success"><?= $success ?></div><?php else: ?>
<form method="POST">
  <label>DB Host</label><input name="db_host" value="localhost" required>
  <label>DB Name</label><input name="db_name" placeholder="parentcontrol" required>
  <label>DB User</label><input name="db_user" required>
  <label>DB Password</label><input name="db_pass" type="password">
  <label>Admin Username</label><input name="admin_user" required>
  <label>Admin Password</label><input name="admin_pass" type="password" required>
  <label>Node.js Backend URL (Render)</label><input name="node_url" placeholder="https://your-app.onrender.com" required>
  <button type="submit">Install Now</button>
</form>
<?php endif; ?>
</body>
</html>
