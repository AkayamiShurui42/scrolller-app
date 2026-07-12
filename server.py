import http.server
import socketserver
import urllib.request
import json
import os
import sys

PORT = 8080
PUBLIC_DIR = os.path.join(os.path.dirname(__file__), 'public')

class ScrolllerProxyHandler(http.server.SimpleHTTPRequestHandler):
    def translate_path(self, path):
        # Override to serve files from the 'public' directory
        path = super().translate_path(path)
        rel_path = os.path.relpath(path, os.getcwd())
        return os.path.join(PUBLIC_DIR, rel_path)

    def do_POST(self):
        if self.path == '/api/graphql':
            content_length = int(self.headers['Content-Length'])
            post_data = self.rfile.read(content_length)
            
            # Forward the request to Scrolller's API
            target_url = "https://api.scrolller.com/admin"
            req = urllib.request.Request(
                target_url,
                data=post_data,
                headers={
                    'Content-Type': 'application/json',
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
                },
                method='POST'
            )
            
            try:
                with urllib.request.urlopen(req) as response:
                    res_body = response.read()
                    
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Access-Control-Allow-Origin', '*')
                    self.end_headers()
                    self.wfile.write(res_body)
            except urllib.error.HTTPError as e:
                err_body = e.read()
                self.send_response(e.code)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(err_body)
            except Exception as e:
                self.send_response(500)
                self.end_headers()
                self.wfile.write(str(e).encode('utf-8'))
        else:
            self.send_response(404)
            self.end_headers()

    def do_OPTIONS(self):
        # Handle CORS preflight requests
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type, Authorization')
        self.end_headers()

def run():
    # Make sure public directory exists
    if not os.path.exists(PUBLIC_DIR):
        print(f"Error: Public directory '{PUBLIC_DIR}' does not exist.")
        sys.exit(1)

    # Disable socket reuse delays
    socketserver.TCPServer.allow_reuse_address = True
    
    with socketserver.TCPServer(("", PORT), ScrolllerProxyHandler) as httpd:
        print(f"==================================================")
        print(f"   SCROLLLER PRO - LOCAL DEVELOPMENT SERVER")
        print(f"==================================================")
        print(f" [*] Running on HTTP local address: http://localhost:{PORT}")
        print(f" [*] Serving static assets from: {PUBLIC_DIR}")
        print(f" [*] API GraphQL proxy target: https://api.scrolller.com/admin")
        print(f"--------------------------------------------------")
        print(f"Press Ctrl+C to stop the server.")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down server gracefully...")
            httpd.server_close()

if __name__ == '__main__':
    run()
