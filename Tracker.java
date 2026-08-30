package com.willflood.rayneovrcontroller;

final class Tracker {
    volatile double deadzone = 2.0;
    volatile double smooth = 0.35;
    final double[] bias = new double[]{0,0,0};
    final double[] angle = new double[]{0,0,0};
    final double[] rate = new double[]{0,0,0};
    private long stillSinceNs = 0;

    synchronized double calibrate(java.util.List<double[]> samples) {
        if (samples.isEmpty()) return 999;
        for (int i=0;i<3;i++) {
            double sum=0, lo=Double.POSITIVE_INFINITY, hi=Double.NEGATIVE_INFINITY;
            for (double[] s: samples) {
                sum += s[i];
                lo = Math.min(lo, s[i]);
                hi = Math.max(hi, s[i]);
            }
            bias[i] = sum / samples.size();
        }
        double spread=0;
        for (int i=0;i<3;i++) {
            double lo=Double.POSITIVE_INFINITY, hi=Double.NEGATIVE_INFINITY;
            for (double[] s: samples) {
                lo=Math.min(lo,s[i]); hi=Math.max(hi,s[i]);
            }
            spread=Math.max(spread,hi-lo);
        }
        return spread;
    }

    synchronized double[][] update(double[] raw, double dt) {
        boolean moving=false;
        for (int i=0;i<3;i++) {
            double r=raw[i]-bias[i];
            if (Math.abs(r)<deadzone) r=0.0; else moving=true;
            rate[i]=rate[i]*smooth+r*(1.0-smooth);
            angle[i]+=rate[i]*dt;
        }
        long now=System.nanoTime();
        if (moving) {
            stillSinceNs=0;
        } else {
            if (stillSinceNs==0) stillSinceNs=now;
            else if ((now-stillSinceNs)>1_000_000_000L) {
                for (int i=0;i<3;i++) {
                    bias[i]+=(raw[i]-bias[i])*0.02;
                    rate[i]=0;
                }
            }
        }
        return new double[][]{angle.clone(),rate.clone()};
    }

    synchronized void recentre() {
        angle[0]=angle[1]=angle[2]=0.0;
    }
}
