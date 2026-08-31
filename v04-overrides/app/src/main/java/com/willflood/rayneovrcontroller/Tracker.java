package com.willflood.rayneovrcontroller;

final class Tracker {
    volatile double deadzone = 2.0;
    volatile double smooth = 0.35;
    volatile boolean autoDrift = true;
    volatile double driftStillSeconds = 0.9;
    volatile double driftLearn = 0.015;

    final double[] bias = new double[]{0,0,0};
    final double[] angle = new double[]{0,0,0};
    final double[] rate = new double[]{0,0,0};
    private long stillSinceNs = 0;

    synchronized double calibrate(java.util.List<double[]> samples) {
        if (samples.isEmpty()) return 999;
        for (int i=0;i<3;i++) {
            double sum=0;
            for (double[] s: samples) sum += s[i];
            bias[i] = sum / samples.size();
            rate[i] = 0;
        }
        double spread=0;
        for (int i=0;i<3;i++) {
            double lo=Double.POSITIVE_INFINITY, hi=Double.NEGATIVE_INFINITY;
            for (double[] s: samples) { lo=Math.min(lo,s[i]); hi=Math.max(hi,s[i]); }
            spread=Math.max(spread,hi-lo);
        }
        stillSinceNs=0;
        return spread;
    }

    synchronized double[][] update(double[] raw, double dt) {
        boolean moving=false;
        double stillBand=Math.max(0.75, deadzone*0.65);
        for (int i=0;i<3;i++) {
            double unbiased=raw[i]-bias[i];
            if (Math.abs(unbiased)>stillBand) moving=true;
            double r=Math.abs(unbiased)<deadzone ? 0.0 : unbiased;
            rate[i]=rate[i]*smooth+r*(1.0-smooth);
            angle[i]+=rate[i]*dt;
        }

        long now=System.nanoTime();
        if (moving || !autoDrift) {
            stillSinceNs=0;
        } else {
            if (stillSinceNs==0) stillSinceNs=now;
            else if ((now-stillSinceNs)>(long)(driftStillSeconds*1_000_000_000L)) {
                for (int i=0;i<3;i++) {
                    bias[i]+=(raw[i]-bias[i])*driftLearn;
                    rate[i]*=0.45;
                    if (Math.abs(rate[i])<0.05) rate[i]=0;
                }
            }
        }
        return new double[][]{angle.clone(),rate.clone()};
    }

    synchronized void recentre() {
        angle[0]=angle[1]=angle[2]=0.0;
        rate[0]=rate[1]=rate[2]=0.0;
    }

    synchronized void bleedYaw(double degrees) {
        if (degrees==0) return;
        if (angle[1]>0) angle[1]=Math.max(0,angle[1]-Math.abs(degrees));
        else if (angle[1]<0) angle[1]=Math.min(0,angle[1]+Math.abs(degrees));
    }
}
