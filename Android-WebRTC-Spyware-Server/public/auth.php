<?php
/**
 * auth.php — include this at the top of any protected page.
 * Redirects to login.php if admin is not authenticated.
 */
if (session_status() === PHP_SESSION_NONE) session_start();
if (empty($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    header('Location: login.php');
    exit;
}
