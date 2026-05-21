<?php
if (file_exists(__DIR__ . '/config.php')) require_once 'config.php';
session_destroy();
header('Location: login.php');
exit;
