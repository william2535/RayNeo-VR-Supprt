from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()

old = '''        double rateX=rate[1]/Math.max(10.0,maxRate), rateY=rate[0]/Math.max(10.0,maxRate);
        double angleX=yawForAngle/Math.max(5.0,fullAngle), angleY=pitchAngle/Math.max(5.0,fullAngle);
        double x,y;
        if(mode==MODE_RATE){x=rateX;y=rateY;}
        else {
            // v0.5.4 COMFORT LOOK: no velocity boost. Fast and slow head turns
            // reaching the same angle request the same stick output.
            x=angleX;y=angleY;
        }

        x=shape(x,responseCurve)*vrGain;
        y=shape(y,responseCurve)*vrGain;
'''
new = '''        double rawRateX=rate[1], rawRateY=rate[0];
        double rateX=rawRateX/Math.max(10.0,maxRate), rateY=rawRateY/Math.max(10.0,maxRate);
        double angleX=yawForAngle/Math.max(5.0,fullAngle), angleY=pitchAngle/Math.max(5.0,fullAngle);
        double x,y;
        if(mode==MODE_RATE){
            // RAW mode is retained as an explicit advanced option.
            x=rateX;y=rateY;
        } else if(mode==MODE_ANGLE){
            // Explicit legacy/experimental angle-hold mode. This intentionally keeps
            // requesting stick while the head remains off-centre.
            x=angleX;y=angleY;
        } else {
            // v0.5.5 COMFORT MOTION: normal head-look is movement-driven, not
            // position-driven. Holding your head still therefore returns the virtual
            // stick to centre. Quick flicks are compressed/capped to avoid the large
            // delayed camera surge that feels disorientating over cloud streaming.
            x=comfortMotion(rawRateX);y=comfortMotion(rawRateY);
        }

        x=shape(x,responseCurve)*vrGain;
        y=shape(y,responseCurve)*vrGain;
'''
if old not in s:
    raise SystemExit('v0.5.5 computeHeadOutput target not found')
s = s.replace(old, new, 1)

old2 = '''    private static double shape(double v,double curve){double a=Math.min(1.0,Math.abs(v));return Math.signum(v)*Math.pow(a,curve);}'''
new2 = '''    private double comfortMotion(double degPerSec){
        double stop=Math.max(2.5,tracker.deadzone);
        double a=Math.abs(degPerSec);
        if(a<=stop)return 0.0;
        // Reach the comfort ceiling by about 32 deg/s. Anything faster gets the same
        // maximum command, so a head flick cannot suddenly become a huge stick shove.
        double t=clamp((a-stop)/Math.max(1.0,32.0-stop),0.0,1.0);
        double mag=0.68*Math.pow(t,0.72);
        return Math.signum(degPerSec)*mag;
    }

    private static double shape(double v,double curve){double a=Math.min(1.0,Math.abs(v));return Math.signum(v)*Math.pow(a,curve);}'''
if old2 not in s:
    raise SystemExit('v0.5.5 helper insertion target not found')
s = s.replace(old2, new2, 1)

p.write_text(s)
print('v0.5.5 ControllerService transform OK')
