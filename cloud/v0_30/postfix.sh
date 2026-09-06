#!/bin/sh
set -eu
FILE="$1"
sed -i "s/emitTo(req.params.userId,{type:'meeting.admitted'/emitTo(String(req.params.userId),{type:'meeting.admitted'/" "$FILE"
sed -i "s/emitTo(req.params.userId,{type:'meeting.denied'/emitTo(String(req.params.userId),{type:'meeting.denied'/" "$FILE"
