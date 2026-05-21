<?php
// FIX — Added brute-force protection (max 5 attempts per IP per 15 min)
session_start();
require_once 'config.php';

if (isset($_SESSION['admin_id'])) {
    header('Location: index.php'); exit;
}

$error = '';

function getDb() {
    return new PDO(
        'mysql:host=' . DB_HOST . ';dbname=' . DB_NAME . ';charset=utf8mb4',
        DB_USER, DB_PASS,
        [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]
    );
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $ip = $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';
    $username = trim($_POST['username'] ?? '');
    $password = $_POST['password'] ?? '';

    try {
        $pdo = getDb();

        // Brute-force check: max 5 attempts per IP in last 15 minutes
        $stmt = $pdo->prepare(
            "SELECT COUNT(*) FROM login_attempts WHERE ip_address = ? AND attempted_at > DATE_SUB(NOW(), INTERVAL 15 MINUTE)"
        );
        $stmt->execute([$ip]);
        $attempts = (int)$stmt->fetchColumn();

        if ($attempts >= 5) {
            $error = 'Too many login attempts. Please wait 15 minutes.';
        } else {
            $stmt = $pdo->prepare('SELECT id, password FROM admins WHERE username = ?');
            $stmt->execute([$username]);
            $admin = $stmt->fetch(PDO::FETCH_ASSOC);

            if ($admin && password_verify($password, $admin['password'])) {
                // Clear attempts on success
                $pdo->prepare('DELETE FROM login_attempts WHERE ip_address = ?')->execute([$ip]);
                session_regenerate_id(true);
                $_SESSION['admin_id'] = $admin['id'];
                $_SESSION['admin_user'] = $username;
                header('Location: index.php'); exit;
            } else {
                // Log failed attempt
                $pdo->prepare('INSERT INTO login_attempts (ip_address) VALUES (?)')->execute([$ip]);
                $error = 'Invalid username or password.';
            }
        }
    } catch (PDOException $e) {
        $error = 'Database error. Please try again.';
    }
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Login — WebRTC ParentControl</title>
<style>
  body { font-family: Arial, sans-serif; background: #1a1a2e; color: #eee; display:flex; align-items:center; justify-content:center; min-height:100vh; margin:0; }
  .box { background: #16213e; padding: 40px; border-radius: 12px; width: 360px; box-shadow: 0 8px 32px rgba(0,0,0,0.5); }
  h2 { color: #e94560; text-align:center; margin-bottom:24px; }
  input { width:100%; padding:12px; margin:6px 0 16px; box-sizing:border-box; border-radius:6px; border:1px solid #444; background:#0f3460; color:#eee; font-size:14px; }
  button { width:100%; background:#e94560; color:#fff; border:none; padding:13px; border-radius:6px; cursor:pointer; font-size:15px; }
  button:hover { background:#c0392b; }
  .error { background:#c0392b22; border:1px solid #e94560; color:#ff6b6b; padding:10px; border-radius:6px; margin-bottom:16px; font-size:13px; }
</style>
</head>
<body>
<div class="box">
  <h2>🔐 ParentControl Login</h2>
  <?php if ($error): ?><div class="error"><?= htmlspecialchars($error) ?></div><?php endif; ?>
  <form method="POST">
    <input type="text"     name="username" placeholder="Username" required autofocus>
    <input type="password" name="password" placeholder="Password" required>
    <button type="submit">Login</button>
  </form>
</div>
</body>
</html>
