package com.willflood.rayneovrcontroller;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * v0.7 AI Depth renderer.
 *
 * The proven Air 4 Pro native SBS path is retained. A low-rate 256x256 copy of
 * the captured frame is fed to MiDaS-small on a worker thread. The returned
 * relative inverse-depth map is temporally smoothed and uploaded as a GL
 * texture. The final SBS shader then applies a different horizontal disparity
 * to near/far pixels for each eye.
 */
public final class SbsGLView extends GLSurfaceView {
    private static final String TAG="RayNeoDepth";

    public interface CaptureSurfaceListener {
        void onCaptureSurfaceReady(Surface surface);
    }

    private final MirrorRenderer renderer;

    public SbsGLView(Context context,int captureWidth,int captureHeight,CaptureSurfaceListener listener){
        super(context);
        setEGLContextClientVersion(2);
        renderer=new MirrorRenderer(this,context.getApplicationContext(),captureWidth,captureHeight,listener);
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setPreserveEGLContextOnPause(true);
    }

    public void setStereoSettings(float separationPx,float zoomPercent,float verticalTrimPx,boolean swapEyes,float aiDepthPercent,float convergencePercent){
        renderer.setStereoSettings(separationPx,zoomPercent,verticalTrimPx,swapEyes,aiDepthPercent,convergencePercent);
        try{requestRender();}catch(Throwable ignored){}
    }

    public void releaseMirror(){
        try{queueEvent(renderer::release);}catch(Throwable ignored){}
        try{onPause();}catch(Throwable ignored){}
    }

    private static final class MirrorRenderer implements Renderer,SurfaceTexture.OnFrameAvailableListener {
        private static final int DEPTH_SIZE=256;
        private static final int DEPTH_INTERVAL=4;
        private static final float DEPTH_TEMPORAL_NEW=0.32f;
        private static final float[] MEAN={0.485f,0.456f,0.406f};
        private static final float[] STD={0.229f,0.224f,0.225f};

        private static final float[] VERTICES={
                -1f,-1f, 0f,0f,
                 1f,-1f, 1f,0f,
                -1f, 1f, 0f,1f,
                 1f, 1f, 1f,1f
        };

        private static final String FINAL_VERT=
                "attribute vec2 aPos;\n"+
                "attribute vec2 aTex;\n"+
                "uniform mat4 uTexMatrix;\n"+
                "uniform vec2 uEyeOffset;\n"+
                "uniform float uZoom;\n"+
                "varying vec2 vTex;\n"+
                "varying vec2 vScreen;\n"+
                "void main(){\n"+
                "  gl_Position=vec4(aPos,0.0,1.0);\n"+
                "  vec2 uv=(aTex-vec2(0.5))/uZoom+vec2(0.5)+uEyeOffset;\n"+
                "  vec4 t=uTexMatrix*vec4(uv,0.0,1.0);\n"+
                "  vTex=t.xy;\n"+
                "  vScreen=aTex;\n"+
                "}\n";

        private static final String FINAL_FRAG=
                "#extension GL_OES_EGL_image_external : require\n"+
                "precision mediump float;\n"+
                "uniform samplerExternalOES uTexture;\n"+
                "uniform sampler2D uDepth;\n"+
                "uniform float uEyeSign;\n"+
                "uniform float uDepthStrength;\n"+
                "uniform float uConvergence;\n"+
                "varying vec2 vTex;\n"+
                "varying vec2 vScreen;\n"+
                "void main(){\n"+
                "  float d=texture2D(uDepth,vScreen).r;\n"+
                "  float disparity=(d-uConvergence)*uDepthStrength*uEyeSign;\n"+
                "  vec2 tc=vec2(clamp(vTex.x+disparity,0.002,0.998),clamp(vTex.y,0.002,0.998));\n"+
                "  gl_FragColor=texture2D(uTexture,tc);\n"+
                "}\n";

        private static final String COPY_VERT=
                "attribute vec2 aPos;\n"+
                "attribute vec2 aTex;\n"+
                "uniform mat4 uTexMatrix;\n"+
                "varying vec2 vTex;\n"+
                "void main(){ gl_Position=vec4(aPos,0.0,1.0); vTex=(uTexMatrix*vec4(aTex,0.0,1.0)).xy; }\n";

        private static final String COPY_FRAG=
                "#extension GL_OES_EGL_image_external : require\n"+
                "precision mediump float;\n"+
                "uniform samplerExternalOES uTexture;\n"+
                "varying vec2 vTex;\n"+
                "void main(){ gl_FragColor=texture2D(uTexture,vTex); }\n";

        private final SbsGLView owner;
        private final Context context;
        private final int captureWidth,captureHeight;
        private final CaptureSurfaceListener listener;
        private final FloatBuffer buffer;
        private final float[] texMatrix=new float[16];
        private final ExecutorService depthExecutor=Executors.newSingleThreadExecutor();
        private final AtomicBoolean inferenceBusy=new AtomicBoolean(false);

        private int finalProgram,copyProgram,textureId,depthTextureId,depthFbo,depthColorTexture;
        private int aPos,aTex,uTexMatrix,uEyeOffset,uZoom,uDepth,uEyeSign,uDepthStrength,uConvergence;
        private int copyPos,copyTex,copyMatrix;
        private int viewWidth,viewHeight,frameCounter=0;
        private SurfaceTexture surfaceTexture;
        private Surface surface;
        private volatile boolean frameAvailable;
        private volatile boolean released=false;

        private volatile float separationPx=0f,zoomPercent=100f,verticalTrimPx=0f;
        private volatile boolean swapEyes=false;
        private volatile float aiDepthPercent=0f,convergencePercent=50f;

        private Interpreter interpreter;
        private volatile boolean modelReady=false;
        private ByteBuffer modelInput;
        private final float[][][] modelOutput=new float[1][DEPTH_SIZE][DEPTH_SIZE];
        private final float[] smoothedDepth=new float[DEPTH_SIZE*DEPTH_SIZE];
        private volatile byte[] pendingDepth;
        private ByteBuffer readback;
        private ByteBuffer depthUpload;

        MirrorRenderer(SbsGLView owner,Context context,int captureWidth,int captureHeight,CaptureSurfaceListener listener){
            this.owner=owner;this.context=context;this.captureWidth=Math.max(1,captureWidth);this.captureHeight=Math.max(1,captureHeight);this.listener=listener;
            ByteBuffer bb=ByteBuffer.allocateDirect(VERTICES.length*4).order(ByteOrder.nativeOrder());
            buffer=bb.asFloatBuffer();buffer.put(VERTICES).position(0);
            Matrix.setIdentityM(texMatrix,0);
            depthExecutor.execute(this::loadDepthModel);
        }

        void setStereoSettings(float separationPx,float zoomPercent,float verticalTrimPx,boolean swapEyes,float aiDepthPercent,float convergencePercent){
            this.separationPx=clamp(separationPx,0f,24f);
            this.zoomPercent=clamp(zoomPercent,100f,115f);
            this.verticalTrimPx=clamp(verticalTrimPx,-8f,8f);
            this.swapEyes=swapEyes;
            this.aiDepthPercent=clamp(aiDepthPercent,0f,100f);
            this.convergencePercent=clamp(convergencePercent,5f,95f);
        }

        private void loadDepthModel(){
            try(InputStream in=context.getAssets().open("midas_small_256_fp16.tflite");ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] chunk=new byte[64*1024];int n;
                while((n=in.read(chunk))>0)out.write(chunk,0,n);
                byte[] bytes=out.toByteArray();
                ByteBuffer model=ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
                model.put(bytes).rewind();
                Interpreter.Options options=new Interpreter.Options();
                options.setNumThreads(Math.max(2,Math.min(4,Runtime.getRuntime().availableProcessors())));
                interpreter=new Interpreter(model,options);
                modelInput=ByteBuffer.allocateDirect(DEPTH_SIZE*DEPTH_SIZE*3*4).order(ByteOrder.nativeOrder());
                modelReady=true;
                Log.i(TAG,"MiDaS depth model ready: "+bytes.length+" bytes");
            }catch(Throwable t){
                modelReady=false;
                Log.e(TAG,"MiDaS model load failed",t);
            }
        }

        @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){
            finalProgram=link(FINAL_VERT,FINAL_FRAG);
            aPos=GLES20.glGetAttribLocation(finalProgram,"aPos");
            aTex=GLES20.glGetAttribLocation(finalProgram,"aTex");
            uTexMatrix=GLES20.glGetUniformLocation(finalProgram,"uTexMatrix");
            uEyeOffset=GLES20.glGetUniformLocation(finalProgram,"uEyeOffset");
            uZoom=GLES20.glGetUniformLocation(finalProgram,"uZoom");
            uDepth=GLES20.glGetUniformLocation(finalProgram,"uDepth");
            uEyeSign=GLES20.glGetUniformLocation(finalProgram,"uEyeSign");
            uDepthStrength=GLES20.glGetUniformLocation(finalProgram,"uDepthStrength");
            uConvergence=GLES20.glGetUniformLocation(finalProgram,"uConvergence");

            copyProgram=link(COPY_VERT,COPY_FRAG);
            copyPos=GLES20.glGetAttribLocation(copyProgram,"aPos");
            copyTex=GLES20.glGetAttribLocation(copyProgram,"aTex");
            copyMatrix=GLES20.glGetUniformLocation(copyProgram,"uTexMatrix");

            int[] t=new int[1];
            GLES20.glGenTextures(1,t,0);textureId=t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);

            surfaceTexture=new SurfaceTexture(textureId);
            surfaceTexture.setDefaultBufferSize(captureWidth,captureHeight);
            surfaceTexture.setOnFrameAvailableListener(this);
            surface=new Surface(surfaceTexture);
            if(listener!=null)listener.onCaptureSurfaceReady(surface);

            setupDepthGl();
            GLES20.glClearColor(0f,0f,0f,1f);
        }

        private void setupDepthGl(){
            int[] a=new int[1];
            GLES20.glGenTextures(1,a,0);depthTextureId=a[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,depthTextureId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            byte[] neutral=new byte[DEPTH_SIZE*DEPTH_SIZE];
            java.util.Arrays.fill(neutral,(byte)128);
            ByteBuffer nb=ByteBuffer.allocateDirect(neutral.length);nb.put(neutral).rewind();
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_LUMINANCE,DEPTH_SIZE,DEPTH_SIZE,0,GLES20.GL_LUMINANCE,GLES20.GL_UNSIGNED_BYTE,nb);

            GLES20.glGenTextures(1,a,0);depthColorTexture=a[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,depthColorTexture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,DEPTH_SIZE,DEPTH_SIZE,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);

            GLES20.glGenFramebuffers(1,a,0);depthFbo=a[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,depthFbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,depthColorTexture,0);
            int status=GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if(status!=GLES20.GL_FRAMEBUFFER_COMPLETE)Log.e(TAG,"Depth FBO incomplete: "+status);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
            readback=ByteBuffer.allocateDirect(DEPTH_SIZE*DEPTH_SIZE*4).order(ByteOrder.nativeOrder());
            depthUpload=ByteBuffer.allocateDirect(DEPTH_SIZE*DEPTH_SIZE).order(ByteOrder.nativeOrder());
        }

        @Override public void onSurfaceChanged(GL10 gl,int width,int height){viewWidth=Math.max(1,width);viewHeight=Math.max(1,height);}

        @Override public void onDrawFrame(GL10 gl){
            if(surfaceTexture==null)return;
            boolean gotFrame=false;
            if(frameAvailable){
                try{surfaceTexture.updateTexImage();surfaceTexture.getTransformMatrix(texMatrix);gotFrame=true;}catch(Throwable ignored){}
                frameAvailable=false;
            }

            uploadPendingDepth();
            if(gotFrame && aiDepthPercent>0.1f && modelReady && !inferenceBusy.get() && (++frameCounter%DEPTH_INTERVAL)==0)captureDepthFrame();

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
            GLES20.glViewport(0,0,viewWidth,viewHeight);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(finalProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,depthTextureId);
            GLES20.glUniform1i(uDepth,1);
            GLES20.glUniformMatrix4fv(uTexMatrix,1,false,texMatrix,0);
            GLES20.glUniform1f(uZoom,Math.max(1f,zoomPercent/100f));
            GLES20.glUniform1f(uDepthStrength,0.020f*(aiDepthPercent/100f));
            GLES20.glUniform1f(uConvergence,convergencePercent/100f);
            bindFinalAttributes();

            int leftWidth=viewWidth/2;
            drawEye(true,0,leftWidth,viewHeight);
            drawEye(false,leftWidth,viewWidth-leftWidth,viewHeight);
        }

        private void bindFinalAttributes(){
            buffer.position(0);GLES20.glEnableVertexAttribArray(aPos);GLES20.glVertexAttribPointer(aPos,2,GLES20.GL_FLOAT,false,16,buffer);
            buffer.position(2);GLES20.glEnableVertexAttribArray(aTex);GLES20.glVertexAttribPointer(aTex,2,GLES20.GL_FLOAT,false,16,buffer);
        }

        private void drawEye(boolean left,int eyeX,int eyeWidth,int eyeHeight){
            if(eyeWidth<=0||eyeHeight<=0)return;
            float sourceAspect=captureWidth/(float)captureHeight,eyeAspect=eyeWidth/(float)eyeHeight;
            int x=eyeX,y=0,w=eyeWidth,h=eyeHeight;
            if(eyeAspect>sourceAspect){w=Math.max(1,Math.round(eyeHeight*sourceAspect));x=eyeX+(eyeWidth-w)/2;}
            else if(eyeAspect<sourceAspect){h=Math.max(1,Math.round(eyeWidth/sourceAspect));y=(eyeHeight-h)/2;}
            float sign=left?1f:-1f;if(swapEyes)sign=-sign;
            float eyeShift=(separationPx*0.5f)/Math.max(1f,captureWidth);
            float vShift=(verticalTrimPx*0.5f)/Math.max(1f,captureHeight);
            GLES20.glUniform2f(uEyeOffset,sign*eyeShift,sign*vShift);
            GLES20.glUniform1f(uEyeSign,sign);
            GLES20.glViewport(x,y,w,h);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        }

        private void captureDepthFrame(){
            if(!inferenceBusy.compareAndSet(false,true))return;
            try{
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,depthFbo);
                GLES20.glViewport(0,0,DEPTH_SIZE,DEPTH_SIZE);
                GLES20.glUseProgram(copyProgram);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
                GLES20.glUniformMatrix4fv(copyMatrix,1,false,texMatrix,0);
                buffer.position(0);GLES20.glEnableVertexAttribArray(copyPos);GLES20.glVertexAttribPointer(copyPos,2,GLES20.GL_FLOAT,false,16,buffer);
                buffer.position(2);GLES20.glEnableVertexAttribArray(copyTex);GLES20.glVertexAttribPointer(copyTex,2,GLES20.GL_FLOAT,false,16,buffer);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
                readback.position(0);
                GLES20.glReadPixels(0,0,DEPTH_SIZE,DEPTH_SIZE,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,readback);
                byte[] frame=new byte[DEPTH_SIZE*DEPTH_SIZE*4];readback.position(0);readback.get(frame);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
                depthExecutor.execute(()->runDepth(frame));
            }catch(Throwable t){
                inferenceBusy.set(false);GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);Log.e(TAG,"Depth capture failed",t);
            }
        }

        private void runDepth(byte[] rgba){
            try{
                Interpreter it=interpreter;if(it==null||released)return;
                ByteBuffer in=modelInput;in.rewind();
                for(int y=0;y<DEPTH_SIZE;y++){
                    int srcY=DEPTH_SIZE-1-y;
                    for(int x=0;x<DEPTH_SIZE;x++){
                        int p=(srcY*DEPTH_SIZE+x)*4;
                        float r=(rgba[p]&255)/255f,g=(rgba[p+1]&255)/255f,b=(rgba[p+2]&255)/255f;
                        in.putFloat((r-MEAN[0])/STD[0]);in.putFloat((g-MEAN[1])/STD[1]);in.putFloat((b-MEAN[2])/STD[2]);
                    }
                }
                in.rewind();it.run(in,modelOutput);
                float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
                for(int y=0;y<DEPTH_SIZE;y++)for(int x=0;x<DEPTH_SIZE;x++){float v=modelOutput[0][y][x];if(Float.isFinite(v)){if(v<min)min=v;if(v>max)max=v;}}
                float range=Math.max(1e-6f,max-min);byte[] out=new byte[DEPTH_SIZE*DEPTH_SIZE];
                for(int y=0;y<DEPTH_SIZE;y++){
                    int glY=DEPTH_SIZE-1-y;
                    for(int x=0;x<DEPTH_SIZE;x++){
                        float n=clamp((modelOutput[0][y][x]-min)/range,0f,1f);
                        int idx=y*DEPTH_SIZE+x;
                        float old=smoothedDepth[idx];float sm=(old==0f)?n:(old*(1f-DEPTH_TEMPORAL_NEW)+n*DEPTH_TEMPORAL_NEW);smoothedDepth[idx]=sm;
                        out[glY*DEPTH_SIZE+x]=(byte)Math.round(sm*255f);
                    }
                }
                pendingDepth=out;owner.requestRender();
            }catch(Throwable t){Log.e(TAG,"Depth inference failed",t);}finally{inferenceBusy.set(false);}
        }

        private void uploadPendingDepth(){
            byte[] p=pendingDepth;if(p==null||depthTextureId==0)return;pendingDepth=null;
            depthUpload.clear();depthUpload.put(p).rewind();
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,depthTextureId);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,DEPTH_SIZE,DEPTH_SIZE,GLES20.GL_LUMINANCE,GLES20.GL_UNSIGNED_BYTE,depthUpload);
        }

        @Override public void onFrameAvailable(SurfaceTexture st){frameAvailable=true;owner.requestRender();}

        void release(){
            released=true;modelReady=false;depthExecutor.shutdownNow();
            try{if(interpreter!=null)interpreter.close();}catch(Throwable ignored){}interpreter=null;
            try{if(surface!=null)surface.release();}catch(Throwable ignored){}surface=null;
            try{if(surfaceTexture!=null)surfaceTexture.release();}catch(Throwable ignored){}surfaceTexture=null;
            int[] one=new int[1];
            if(textureId!=0){one[0]=textureId;GLES20.glDeleteTextures(1,one,0);textureId=0;}
            if(depthTextureId!=0){one[0]=depthTextureId;GLES20.glDeleteTextures(1,one,0);depthTextureId=0;}
            if(depthColorTexture!=0){one[0]=depthColorTexture;GLES20.glDeleteTextures(1,one,0);depthColorTexture=0;}
            if(depthFbo!=0){one[0]=depthFbo;GLES20.glDeleteFramebuffers(1,one,0);depthFbo=0;}
            if(finalProgram!=0){GLES20.glDeleteProgram(finalProgram);finalProgram=0;}
            if(copyProgram!=0){GLES20.glDeleteProgram(copyProgram);copyProgram=0;}
        }

        private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
        private static int compile(int type,String src){int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,src);GLES20.glCompileShader(s);int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]==0){String log=GLES20.glGetShaderInfoLog(s);GLES20.glDeleteShader(s);throw new RuntimeException("Shader compile failed: "+log);}return s;}
        private static int link(String vs,String fs){int v=compile(GLES20.GL_VERTEX_SHADER,vs),f=compile(GLES20.GL_FRAGMENT_SHADER,fs),p=GLES20.glCreateProgram();GLES20.glAttachShader(p,v);GLES20.glAttachShader(p,f);GLES20.glLinkProgram(p);GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);int[] ok=new int[1];GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);if(ok[0]==0){String log=GLES20.glGetProgramInfoLog(p);GLES20.glDeleteProgram(p);throw new RuntimeException("Program link failed: "+log);}return p;}
    }
}
