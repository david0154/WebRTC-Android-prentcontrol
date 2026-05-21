<?php
/**
 * config.js.php
 * Outputs RENDER_SERVER_URL as a JavaScript variable.
 * Loaded as <script src="config.js.php"></script> in index.php
 *
 * HOW TO CHANGE YOUR RENDER URL:
 *   Option 1: Edit $RENDER_SERVER_URL below directly.
 *   Option 2: Set a PHP env variable on your host:
 *             export RENDER_SERVER_URL=https://yourapp.onrender.com
 */
header('Content-Type: application/javascript');

// ── EDIT THIS LINE ────────────────────────────────────────────────────────────
$RENDER_SERVER_URL = getenv('RENDER_SERVER_URL') ?: 'https://hypewebrtc.onrender.com';
// ─────────────────────────────────────────────────────────────────────────────
?>
const RENDER_SERVER_URL = <?= json_encode(rtrim($RENDER_SERVER_URL, '/')) ?>;
