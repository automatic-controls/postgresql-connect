#!/bin/bash

# Crontab example:
# 47 23 1 * * /bin/bash /data/ssl/renew.sh

# Decsriptive name for this server
name='PostgreSQL Database Server'
# Keystore password
password='PASSWORD'
# Recipient email addresses for notifications (comma-delimited)
recipients='cvogt@automaticcontrols.net,cvogt729@gmail.com'
# DNS name for your SSL certificate
dns='postgresql.domain.com'
# Distinguished name for your SSL certificate
dname="CN=$dns, OU=IT, O=Automatic Controls Equipment Systems\, Inc., L=Fenton, ST=MO, C=US"
# Working directory
folder='/data/ssl/certbot'
# Final server certificate
pem_cert='/data/ssl/ssl_cert'
# Final server private key
pem_key='/data/ssl/ssl_key'

# Function to send an email
function send_email(){
msmtp -t <<EOF
To: $recipients
Subject: $1

$2
EOF
}

# Create working directory
sudo rm -Rf "$folder"
sudo mkdir "$folder"
if ! cd "$folder"; then
  send_email "$name SSL Renewal Failure" 'Monthly certificate renewal script failed to locate SSL directory.'
  exit 1
fi

# Generate a new key
if ! sudo keytool -keystore store -storepass "$password" -genkeypair -alias server -keyalg RSA -sigalg SHA256withRSA -keysize 2048 -keypass "$password" -validity 365 -ext "san=dns:$dns" -dname "$dname"; then
  send_email "$name SSL Renewal Failure" 'Monthly certificate renewal script failed to create a new 2048 bit RSA key-pair.'
  exit 1
fi

# Generate a certificate request
if ! sudo keytool -keystore store -storepass "$password" -alias server -certreq -file 'request.csr'; then
  send_email "$name SSL Renewal Failure" 'Monthly certificate renewal script failed to generate CSR.'
  exit 1
fi

# Open HTTP and HTTPS ports
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp

# Use certbot to sign the certificate request
sudo certbot certonly --standalone --csr "$folder/request.csr" --logs-dir "$folder/logs"
err="$?"

# Close HTTP and HTTPS ports
sudo ufw delete allow 80/tcp
sudo ufw delete allow 443/tcp

# Handle certbot failure
if [[ "$err" != '0' ]]; then
  sudo rm -f 'request.csr'
  sudo rm -f ./*.pem
  send_email "$name SSL Renewal Failure" "Monthly certificate renewal script failed: certbot encountered error. Please see '$folder/logs' for more details."
  exit 1
fi

# Apply changes
sudo mv -f "$(find "/data/ssl/certbot" -maxdepth 1 -name '*_chain.pem' | sort -n | tail -n 1)" "$pem_cert"
sudo openssl pkcs12 -in store -passin "pass:$password" -nocerts -noenc | sudo openssl rsa -out "$pem_key"
sudo chown postgres:postgres "$pem_key"
sudo chown postgres:postgres "$pem_cert"
sudo chmod 600 "$pem_key"
sudo chmod 666 "$pem_cert"
sudo systemctl reload postgresql

# Clean files
cd ..
sudo rm -Rf "$folder"