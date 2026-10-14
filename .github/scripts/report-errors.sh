#!/usr/bin/env bash
# Turns compiler errors and test failures into GitHub annotations, so they
# can be read from the checks API without downloading logs.
log=${1:-build.log}
n=0
if [[ -f "$log" ]]; then
  grep -E "^e: |^\s*> .*(failed|error)|What went wrong|Could not resolve|Unresolved reference" "$log" | head -40 | while IFS= read -r line; do
    echo "::error title=build::${line//%/%25}"
  done
fi
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
count = 0
for f in glob.glob("**/build/test-results/**/*.xml", recursive=True):
    for tc in ET.parse(f).getroot().iter("testcase"):
        for fail in list(tc.findall("failure")) + list(tc.findall("error")):
            msg = (fail.get("message") or fail.text or "").strip().replace("\n", " | ")[:900]
            print(f"::error title=test {tc.get('classname')}.{tc.get('name')}::{msg}")
            count += 1
            if count >= 40: raise SystemExit
PY
