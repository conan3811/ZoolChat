#!/bin/sh
set -eu
FILE="$1"
# Express 5 route params can be typed as string|string[] in this build. Force the
# meeting/user route params to strings before sending realtime events.
sed -i "s/emitTo(String(req.params.userId),{type:'meeting.admitted',data:{meetingId:req.params.id}})/emitTo(req.params.userId as string,{type:'meeting.admitted',data:{meetingId:String(req.params.id)}})/" "$FILE"
sed -i "s/emitTo(String(req.params.userId),{type:'meeting.denied',data:{meetingId:req.params.id}})/emitTo(req.params.userId as string,{type:'meeting.denied',data:{meetingId:String(req.params.id)}})/" "$FILE"
sed -i "s/emitTo(req.params.userId,{type:'meeting.admitted',data:{meetingId:req.params.id}})/emitTo(req.params.userId as string,{type:'meeting.admitted',data:{meetingId:String(req.params.id)}})/" "$FILE"
sed -i "s/emitTo(req.params.userId,{type:'meeting.denied',data:{meetingId:req.params.id}})/emitTo(req.params.userId as string,{type:'meeting.denied',data:{meetingId:String(req.params.id)}})/" "$FILE"
