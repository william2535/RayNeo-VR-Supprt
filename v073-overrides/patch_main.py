from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text()

def one(old,new,label):
    global s
    c=s.count(old)
    if c!=1:
        raise SystemExit(f'v0.7.3 main patch {label}: expected 1, got {c}')
    s=s.replace(old,new,1)

one(
'''            if(sbsStatus!=null)sbsStatus.setText("Choose what Android may capture for the SBS mirror…");
            startActivityForResult(mpm.createScreenCaptureIntent(),SBS_CAPTURE_REQ);''',
'''            Intent captureIntent;
            if(Build.VERSION.SDK_INT>=34){
                // Android 14+ normally offers single-app capture. That can leave
                // the RayNeo output frozen/black as soon as the user switches from
                // this app to Minecraft. Force the DEFAULT DISPLAY so the SBS
                // mirror follows launcher -> Minecraft -> any other foreground app.
                captureIntent=mpm.createScreenCaptureIntent(
                        android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay());
                if(sbsStatus!=null)sbsStatus.setText("Android 14+: ENTIRE SCREEN capture forced • approve screen sharing…");
            }else{
                captureIntent=mpm.createScreenCaptureIntent();
                if(sbsStatus!=null)sbsStatus.setText("Choose ENTIRE SCREEN for the SBS mirror…");
            }
            startActivityForResult(captureIntent,SBS_CAPTURE_REQ);''',
'force full display MediaProjection')

p.write_text(s)
print('v0.7.3 force-entire-display MediaProjection transform OK')
