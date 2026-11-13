"""Builds the app's reader page from the web version.

Usage: python3 tools/make-app-page.py
Reads web/fic-nook.html and writes app/src/main/assets/www/index.html:
the helper scripts point at the bundled copies in www/lib/ (so the app works
offline) and the page gets a full <html>/<head>/<body> wrapper.
"""
import os

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = os.path.join(root, "web", "fic-nook.html")
out = os.path.join(root, "app", "src", "main", "assets", "www", "index.html")

s = open(src, encoding="utf-8").read()
app = s.replace("https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/", "lib/")
app = app.replace("https://cdnjs.cloudflare.com/ajax/libs/jszip/3.10.1/jszip.min.js", "lib/jszip.min.js")
app = app.replace("Check that you're online and try again.", "Try closing and reopening the app.")
assert "cdnjs" not in app, "a CDN link is left in the page"
head_end = app.index("<style>")
app = ('<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n'
       '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
       + app[:head_end] + app[head_end:].replace("</style>", "</style>\n</head>\n<body>", 1)
       + "\n</body>\n</html>\n")
open(out, "w", encoding="utf-8").write(app)
print("wrote", os.path.relpath(out, root))
