<?php
/**
 * config.php — Admin credentials.
 *
 * To change password: run this in PHP CLI:
 *   php -r "echo password_hash('your_new_password', PASSWORD_BCRYPT);" 
 * Then paste the hash below.
 *
 * DEFAULT CREDENTIALS:
 *   username: admin
 *   password: admin123
 *   (CHANGE BEFORE DEPLOYING)
 */

define('ADMIN_USERNAME',      'admin');
define('ADMIN_PASSWORD_HASH', '$2y$12$XXXXXXXXXXXXXXXXXXXXXXXX.XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX');
// ^^ Replace this hash with: password_hash('your_password', PASSWORD_BCRYPT)
// Quick test hash for 'admin123' — CHANGE IN PRODUCTION:
// $2y$12$samplehashforadmin123xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
