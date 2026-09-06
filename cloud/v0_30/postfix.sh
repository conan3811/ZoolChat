#!/bin/sh
set -eu
FILE="$1"
# Express 5 route params can be typed as string|string[] in this build. Force the
# meeting/user route params to strings before sending realtime events.
sed -i "s/emitTo(String(req.params.userId),{type:'meeting.admitted',data:{meetingId:req.params.id}})/emitTo(req.params.userId as string,{type:'meeting.admitted',data:{meetingId:String(req.params.id)}})/" "$FILE"
sed -i "s/emitTo(String(req.params.userId),{type:'meeting.denied',data:{meetingId:req.params.id}})/emitTo(req.params.userId as string,{type:'meeting.denied',data:{meetingId:String(req.params.id)}})/" "$FILE"
sed -i "s/emitTo(req.params.userId,{type:'meeting.admitted',data:{meetingId:req.params.id}})/emitTo(req.params.userId as string,{type:'meeting.admitted',data:{meetingId:String(req.params.id)}})/" "$FILE"
sed -i "s/emitTo(req.params.userId,{type:'meeting.denied',data:{meetingId:req.params.id}})/emitTo(req.params.userId as string,{type:'meeting.denied',data:{meetingId:String(req.params.id)}})/" "$FILE"

# Keep runtime/package diagnostics aligned with the v0.30 API overlay.
sed -i 's/ZoolChat server v0\.27 listening/ZoolChat server v0.30 listening/g' "$FILE"
SERVER_DIR=$(dirname "$(dirname "$FILE")")
node - "$SERVER_DIR/package.json" "$SERVER_DIR/package-lock.json" <<'NODE'
const fs = require('fs');
for (const p of process.argv.slice(2)) {
  if (!fs.existsSync(p)) continue;
  const j = JSON.parse(fs.readFileSync(p, 'utf8'));
  j.version = '0.30.0';
  if (j.packages && j.packages['']) j.packages[''].version = '0.30.0';
  fs.writeFileSync(p, JSON.stringify(j, null, 2) + '\n');
}
NODE
