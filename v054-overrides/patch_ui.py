from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()
old = 'turnThresholdLabel=label(root,"Turn assist starts at 40° yaw");turnThreshold=bar(root,20,70,40);'
new = 'turnThresholdLabel=label(root,"360° edge starts at 75° yaw — near full left/right");turnThreshold=bar(root,70,80,75);'
if old not in s:
    raise SystemExit('v0.5.4 UI patch target not found')
s = s.replace(old, new, 1)
old_note = '360° is optional. MOTION-ONLY keeps the useful endless-turn idea but removes the \'carry on\' effect when you hold your head still at the edge.'
new_note = '360° is optional and now EDGE-ONLY: it will not engage during normal looking around. It waits until you are almost at the full left/right head limit. v0.5.4 COMFORT LOOK also removes the fast-head-move speed boost.'
if old_note in s:
    s = s.replace(old_note, new_note, 1)
p.write_text(s)
