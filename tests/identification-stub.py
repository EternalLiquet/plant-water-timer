"""CI browser boundary only. Never started by the app or packaged as real identification."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.respond({'status':'ready'})
    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length','0')))
        self.respond({'status':'suggestions','modelVersion':'ci-stub-not-real-inference','manualFallback':True,'candidates':[{'scientificName':'Monstera deliciosa','confidence':0.72}]})
    def respond(self,value):
        data=json.dumps(value).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
    def log_message(self,*args): pass
HTTPServer(('127.0.0.1',8765),Handler).serve_forever()
