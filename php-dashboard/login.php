<?php
if (!file_exists(__DIR__ . '/config.php')) {
    header('Location: install.php');
    exit;
}
require_once 'config.php';

if (isset($_SESSION['admin_id'])) {
    header('Location: index.php');
    exit;
}

$error = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $username = trim($_POST['username'] ?? '');
    $password = trim($_POST['password'] ?? '');
    try {
        $pdo = new PDO("mysql:host=" . DB_HOST . ";dbname=" . DB_NAME . ";charset=utf8mb4", DB_USER, DB_PASS, [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION
        ]);
        $stmt = $pdo->prepare("SELECT * FROM admins WHERE username = ? LIMIT 1");
        $stmt->execute([$username]);
        $admin = $stmt->fetch(PDO::FETCH_ASSOC);
        if ($admin && password_verify($password, $admin['password'])) {
            $_SESSION['admin_id'] = $admin['id'];
            $_SESSION['admin_user'] = $admin['username'];
            header('Location: index.php');
            exit;
        } else {
            $error = 'Invalid username or password.';
        }
    } catch (Exception $e) {
        $error = 'DB error: ' . $e->getMessage();
    }
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Login – ParentControl</title>
<style>
body{font-family:Arial,sans-serif;background:#0d1117;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0}
.box{background:#161b22;border:1px solid #30363d;border-radius:12px;padding:2.5rem;width:340px;box-shadow:0 8px 30px rgba(0,0,0,.5)}
h2{color:#58a6ff;margin:0 0 1.5rem;text-align:center}input{width:100%;padding:10px 12px;background:#0d1117;border:1px solid #30363d;border-radius:6px;color:#e6edf3;margin-bottom:14px;font-size:14px;box-sizing:border-box}
input:focus{outline:none;border-color:#58a6ff}button{width:100%;padding:11px;background:#1f6feb;border:none;border-radius:6px;color:#fff;font-size:15px;cursor:pointer;font-weight:600}
button:hover{background:#388bfd}.err{color:#f85149;font-size:.85rem;margin-bottom:10px;text-align:center}
</style>
</head>
<body>
<div class="box">
  <h2>🔐 ParentControl Login</h2>
  <?php if ($error): ?><div class="err"><?= htmlspecialchars($error) ?></div><?php endif; ?>
  <form method="POST">
    <input type="text" name="username" placeholder="Username" required autofocus>
    <input type="password" name="password" placeholder="Password" required>
    <button type="submit">Login</button>
  </form>
</div>
</body>
</html>
