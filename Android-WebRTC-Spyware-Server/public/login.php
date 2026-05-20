<?php
session_start();

require_once __DIR__ . '/../config.php';

// ---- Brute-force lockout ----
$ip          = $_SERVER['REMOTE_ADDR'] ?? 'unknown';
$lockoutKey  = 'lockout_' . md5($ip);
$attemptsKey = 'attempts_' . md5($ip);
$lockoutTime = 15 * 60; // 15 minutes
$maxAttempts = 5;

if (!isset($_SESSION[$lockoutKey])) $_SESSION[$lockoutKey]  = 0;
if (!isset($_SESSION[$attemptsKey])) $_SESSION[$attemptsKey] = 0;

$isLocked   = false;
$lockMsg    = '';
if ($_SESSION[$lockoutKey] > 0 && time() < $_SESSION[$lockoutKey]) {
    $isLocked = true;
    $remaining = ceil(($_SESSION[$lockoutKey] - time()) / 60);
    $lockMsg = "Too many failed attempts. Try again in {$remaining} minute(s).";
} elseif ($_SESSION[$lockoutKey] > 0 && time() >= $_SESSION[$lockoutKey]) {
    // Reset after lockout expires
    $_SESSION[$lockoutKey]  = 0;
    $_SESSION[$attemptsKey] = 0;
}

// ---- Already logged in ----
if (isset($_SESSION['admin_logged_in']) && $_SESSION['admin_logged_in'] === true) {
    header('Location: index.php');
    exit;
}

// ---- CSRF token generation ----
if (empty($_SESSION['csrf_token'])) {
    $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
}

$error  = '';
$notice = '';

// ---- Handle POST ----
if ($_SERVER['REQUEST_METHOD'] === 'POST' && !$isLocked) {
    $csrfOk = isset($_POST['csrf_token']) && hash_equals($_SESSION['csrf_token'], $_POST['csrf_token']);
    if (!$csrfOk) {
        $error = 'Invalid request. Please reload and try again.';
    } else {
        $username = trim($_POST['username'] ?? '');
        $password = $_POST['password'] ?? '';

        if ($username === ADMIN_USERNAME && password_verify($password, ADMIN_PASSWORD_HASH)) {
            // Successful login
            session_regenerate_id(true);
            $_SESSION['admin_logged_in'] = true;
            $_SESSION[$attemptsKey]      = 0;
            $_SESSION[$lockoutKey]       = 0;
            // Regenerate CSRF after login
            $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
            header('Location: index.php');
            exit;
        } else {
            $_SESSION[$attemptsKey]++;
            $left = $maxAttempts - $_SESSION[$attemptsKey];
            if ($_SESSION[$attemptsKey] >= $maxAttempts) {
                $_SESSION[$lockoutKey] = time() + $lockoutTime;
                $error = 'Too many failed attempts. Account locked for 15 minutes.';
                $isLocked = true;
            } else {
                $error = "Invalid credentials. {$left} attempt(s) remaining.";
            }
        }
    }
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Admin Login — Surveillance Hub</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: 'Courier New', monospace;
            background: linear-gradient(135deg, #0d0d0d 0%, #1a1a1a 100%);
            color: #00ff00;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        .login-box {
            background: linear-gradient(135deg, #0a0f1c, #1c2526);
            border: 1px solid #00ff00;
            border-radius: 16px;
            padding: 40px 36px;
            width: 100%;
            max-width: 420px;
            box-shadow: 0 0 40px rgba(0,255,0,0.15);
        }
        h1 {
            font-size: 1.4rem;
            text-align: center;
            margin-bottom: 8px;
            color: #00ff00;
            text-shadow: 0 0 10px rgba(0,255,0,0.5);
        }
        .subtitle {
            text-align: center;
            font-size: 0.78rem;
            color: #00aa00;
            margin-bottom: 28px;
        }
        label {
            display: block;
            font-size: 0.82rem;
            color: #00cc00;
            margin-bottom: 6px;
            margin-top: 16px;
        }
        input[type=text], input[type=password] {
            width: 100%;
            background: #0d0d0d;
            border: 1px solid #00ff00;
            border-radius: 8px;
            color: #00ff00;
            padding: 10px 14px;
            font-family: 'Courier New', monospace;
            font-size: 0.95rem;
            outline: none;
            transition: box-shadow .2s;
        }
        input:focus {
            box-shadow: 0 0 10px rgba(0,255,0,0.35);
        }
        .btn {
            margin-top: 24px;
            width: 100%;
            padding: 11px;
            background: linear-gradient(135deg, #00ff00, #00cc00);
            color: #0d0d0d;
            border: none;
            border-radius: 8px;
            font-family: 'Courier New', monospace;
            font-size: 1rem;
            font-weight: 700;
            cursor: pointer;
            transition: all .2s;
        }
        .btn:hover:not(:disabled) {
            background: linear-gradient(135deg, #00cc00, #009900);
            box-shadow: 0 0 16px rgba(0,255,0,0.4);
        }
        .btn:disabled { opacity: .45; cursor: not-allowed; }
        .error {
            background: rgba(255,0,0,0.12);
            border: 1px solid #ff4444;
            color: #ff6666;
            border-radius: 8px;
            padding: 10px 14px;
            margin-top: 16px;
            font-size: 0.85rem;
        }
        .notice {
            background: rgba(0,255,0,0.08);
            border: 1px solid #00ff00;
            color: #00ff00;
            border-radius: 8px;
            padding: 10px 14px;
            margin-top: 16px;
            font-size: 0.85rem;
        }
        .lock-icon {
            text-align: center;
            font-size: 2.5rem;
            margin-bottom: 10px;
        }
    </style>
</head>
<body>
<div class="login-box">
    <div class="lock-icon">🔐</div>
    <h1>Surveillance Hub</h1>
    <p class="subtitle">Admin Access Only</p>

    <?php if ($error):   ?><div class="error"><?= htmlspecialchars($error) ?></div><?php endif; ?>
    <?php if ($lockMsg): ?><div class="error"><?= htmlspecialchars($lockMsg) ?></div><?php endif; ?>
    <?php if ($notice):  ?><div class="notice"><?= htmlspecialchars($notice) ?></div><?php endif; ?>

    <form method="POST" action="login.php" autocomplete="off">
        <input type="hidden" name="csrf_token" value="<?= htmlspecialchars($_SESSION['csrf_token']) ?>">

        <label for="username">Username</label>
        <input type="text" id="username" name="username" required autofocus
               placeholder="admin" maxlength="64"
               value="<?= htmlspecialchars($_POST['username'] ?? '') ?>">

        <label for="password">Password</label>
        <input type="password" id="password" name="password" required
               placeholder="••••••••" maxlength="128">

        <button type="submit" class="btn" <?= $isLocked ? 'disabled' : '' ?>>LOGIN</button>
    </form>
</div>
</body>
</html>
