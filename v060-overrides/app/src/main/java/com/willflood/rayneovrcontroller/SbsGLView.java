package com.willflood.rayneovrcontroller;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * v0.6 Stereo Depth Lab renderer.
 *
 * RayNeo handles native SBS in hardware. This renderer keeps the proven
 * LEFT | RIGHT layout and adds a deliberately conservative per-eye texture
 * offset. It does not claim to reconstruct scene geometry from a mono stream:
 * the disparity moves the captured image plane in stereo space so we can tune
 * a comfortable baseline before attempting any depth-map reconstruction.
 */
public final class SbsGLView extends GLSurfaceView {
    public interface CaptureSurfaceListener {
        void onCaptureSurfaceReady(Surface surface);
    }

    private final MirrorRenderer renderer;

    public SbsGLView(Context context, int captureWidth, int captureHeight, CaptureSurfaceListener listener) {
        super(context);
        setEGLContextClientVersion(2);
        renderer = new MirrorRenderer(this, captureWidth, captureHeight, listener);
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setPreserveEGLContextOnPause(true);
    }

    public void setStereoSettings(float separationPx, float zoomPercent, float verticalTrimPx, boolean swapEyes) {
        renderer.setStereoSettings(separationPx, zoomPercent, verticalTrimPx, swapEyes);
        try { requestRender(); } catch (Throwable ignored) {}
    }

    public void releaseMirror() {
        try { queueEvent(renderer::release); } catch (Throwable ignored) {}
        try { onPause(); } catch (Throwable ignored) {}
    }

    private static final class MirrorRenderer implements Renderer, SurfaceTexture.OnFrameAvailableListener {
        // SurfaceTexture supplies producer orientation. These are normal GL UVs.
        private static final float[] VERTICES = {
                -1f, -1f,  0f, 0f,
                 1f, -1f,  1f, 0f,
                -1f,  1f,  0f, 1f,
                 1f,  1f,  1f, 1f
        };

        private static final String VERT =
                "attribute vec2 aPos;\n" +
                "attribute vec2 aTex;\n" +
                "uniform mat4 uTexMatrix;\n" +
                "uniform vec2 uEyeOffset;\n" +
                "uniform float uZoom;\n" +
                "varying vec2 vTex;\n" +
                "void main(){\n" +
                "  gl_Position=vec4(aPos,0.0,1.0);\n" +
                "  vec2 uv=(aTex-vec2(0.5))/uZoom+vec2(0.5)+uEyeOffset;\n" +
                "  vec4 t=uTexMatrix*vec4(uv,0.0,1.0);\n" +
                "  vTex=t.xy;\n" +
                "}\n";

        private static final String FRAG =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "uniform samplerExternalOES uTexture;\n" +
                "varying vec2 vTex;\n" +
                "void main(){ gl_FragColor=texture2D(uTexture,vTex); }\n";

        private final SbsGLView owner;
        private final int captureWidth, captureHeight;
        private final CaptureSurfaceListener listener;
        private final FloatBuffer buffer;
        private final float[] texMatrix = new float[16];
        private int program, textureId, aPos, aTex, uTexMatrix, uEyeOffset, uZoom;
        private int viewWidth, viewHeight;
        private SurfaceTexture surfaceTexture;
        private Surface surface;
        private volatile boolean frameAvailable;

        // Total disparity is split equally between left and right eyes.
        private volatile float separationPx = 0f;
        private volatile float zoomPercent = 100f;
        private volatile float verticalTrimPx = 0f;
        private volatile boolean swapEyes = false;

        MirrorRenderer(SbsGLView owner, int captureWidth, int captureHeight, CaptureSurfaceListener listener) {
            this.owner=owner;
            this.captureWidth=Math.max(1,captureWidth);
            this.captureHeight=Math.max(1,captureHeight);
            this.listener=listener;
            ByteBuffer bb=ByteBuffer.allocateDirect(VERTICES.length*4).order(ByteOrder.nativeOrder());
            buffer=bb.asFloatBuffer();
            buffer.put(VERTICES).position(0);
            Matrix.setIdentityM(texMatrix,0);
        }

        void setStereoSettings(float separationPx, float zoomPercent, float verticalTrimPx, boolean swapEyes) {
            this.separationPx=clamp(separationPx,0f,24f);
            this.zoomPercent=clamp(zoomPercent,100f,115f);
            this.verticalTrimPx=clamp(verticalTrimPx,-8f,8f);
            this.swapEyes=swapEyes;
        }

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            program=link(VERT,FRAG);
            aPos=GLES20.glGetAttribLocation(program,"aPos");
            aTex=GLES20.glGetAttribLocation(program,"aTex");
            uTexMatrix=GLES20.glGetUniformLocation(program,"uTexMatrix");
            uEyeOffset=GLES20.glGetUniformLocation(program,"uEyeOffset");
            uZoom=GLES20.glGetUniformLocation(program,"uZoom");

            int[] t=new int[1];
            GLES20.glGenTextures(1,t,0);
            textureId=t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);

            surfaceTexture=new SurfaceTexture(textureId);
            surfaceTexture.setDefaultBufferSize(captureWidth,captureHeight);
            surfaceTexture.setOnFrameAvailableListener(this);
            surface=new Surface(surfaceTexture);
            if(listener!=null) listener.onCaptureSurfaceReady(surface);
            GLES20.glClearColor(0f,0f,0f,1f);
        }

        @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
            viewWidth=Math.max(1,width);
            viewHeight=Math.max(1,height);
        }

        @Override public void onDrawFrame(GL10 gl) {
            if(surfaceTexture==null) return;
            if(frameAvailable){
                try {
                    surfaceTexture.updateTexImage();
                    surfaceTexture.getTransformMatrix(texMatrix);
                } catch(Throwable ignored) {}
                frameAvailable=false;
            }

            GLES20.glViewport(0,0,viewWidth,viewHeight);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
            GLES20.glUniformMatrix4fv(uTexMatrix,1,false,texMatrix,0);
            GLES20.glUniform1f(uZoom,Math.max(1f,zoomPercent/100f));

            buffer.position(0);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos,2,GLES20.GL_FLOAT,false,16,buffer);
            buffer.position(2);
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glVertexAttribPointer(aTex,2,GLES20.GL_FLOAT,false,16,buffer);

            int leftWidth=viewWidth/2;
            drawEye(true,0,leftWidth,viewHeight);
            drawEye(false,leftWidth,viewWidth-leftWidth,viewHeight);
        }

        private void drawEye(boolean left,int eyeX,int eyeWidth,int eyeHeight){
            if(eyeWidth<=0||eyeHeight<=0)return;
            float sourceAspect=captureWidth/(float)captureHeight;
            float eyeAspect=eyeWidth/(float)eyeHeight;
            int x=eyeX,y=0,w=eyeWidth,h=eyeHeight;
            if(eyeAspect>sourceAspect){
                w=Math.max(1,Math.round(eyeHeight*sourceAspect));
                x=eyeX+(eyeWidth-w)/2;
            }else if(eyeAspect<sourceAspect){
                h=Math.max(1,Math.round(eyeWidth/sourceAspect));
                y=(eyeHeight-h)/2;
            }

            float sign=left?1f:-1f;
            if(swapEyes)sign=-sign;
            float eyeShift=(separationPx*0.5f)/Math.max(1f,captureWidth);
            float vShift=(verticalTrimPx*0.5f)/Math.max(1f,captureHeight);
            GLES20.glUniform2f(uEyeOffset,sign*eyeShift,sign*vShift);
            GLES20.glViewport(x,y,w,h);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        }

        @Override public void onFrameAvailable(SurfaceTexture st) {
            frameAvailable=true;
            owner.requestRender();
        }

        void release() {
            try { if(surface!=null) surface.release(); } catch (Throwable ignored) {}
            surface=null;
            try { if(surfaceTexture!=null) surfaceTexture.release(); } catch (Throwable ignored) {}
            surfaceTexture=null;
            if(textureId!=0){int[] t={textureId};GLES20.glDeleteTextures(1,t,0);textureId=0;}
            if(program!=0){GLES20.glDeleteProgram(program);program=0;}
        }

        private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}

        private static int compile(int type,String src){
            int s=GLES20.glCreateShader(type);
            GLES20.glShaderSource(s,src);
            GLES20.glCompileShader(s);
            int[] ok=new int[1];
            GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
            if(ok[0]==0){
                String log=GLES20.glGetShaderInfoLog(s);
                GLES20.glDeleteShader(s);
                throw new RuntimeException("Shader compile failed: "+log);
            }
            return s;
        }

        private static int link(String vs,String fs){
            int v=compile(GLES20.GL_VERTEX_SHADER,vs),f=compile(GLES20.GL_FRAGMENT_SHADER,fs),p=GLES20.glCreateProgram();
            GLES20.glAttachShader(p,v);
            GLES20.glAttachShader(p,f);
            GLES20.glLinkProgram(p);
            GLES20.glDeleteShader(v);
            GLES20.glDeleteShader(f);
            int[] ok=new int[1];
            GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
            if(ok[0]==0){
                String log=GLES20.glGetProgramInfoLog(p);
                GLES20.glDeleteProgram(p);
                throw new RuntimeException("Program link failed: "+log);
            }
            return p;
        }
    }
}
