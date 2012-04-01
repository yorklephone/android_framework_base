
/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view.animation;

import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.graphics.Camera;
import android.graphics.Matrix;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;



/**
 * An animation that rotates the view on the Y axis between two specified angles.
 * This animation also adds a translation on the Z axis (depth) to improve the effect.
 */
public class Rotate3dAnimation extends Animation {
    private final float mFromDegrees;
    private final float mToDegrees;
    private  float mCenterX;
    private float mCenterY;
    private final float mDepthZ;
    private final boolean mReverse;
    private Camera mCamera;
    private  int mCenterXType = 0;
    private  int mCenterYType = 0;
    private float mCenterXValue = 0.0f;
    private float mCenterYValue = 0.0f;
    
    /**
     * Constructor used when a Rotate3DAnimation is loaded from a resource.
     * 
     * @param context Application context to use
     * @param attrs Attribute set from which to read values
     */
    public Rotate3dAnimation(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.Rotate3dAnimation);

        mFromDegrees = a.getFloat(
                com.android.internal.R.styleable.Rotate3dAnimation_fromDegrees3d, 0.0f);
        mToDegrees = a.getFloat(com.android.internal.R.styleable.Rotate3dAnimation_toDegrees3d, 0.0f);


    Description d = Description.parseValue(a.peekValue(
            com.android.internal.R.styleable.Rotate3dAnimation_centerX3d));
        mCenterXValue = d.value;
        mCenterXType = d.type;

        d = Description.parseValue(a.peekValue(
                com.android.internal.R.styleable.Rotate3dAnimation_centerY3d));
        mCenterYValue = d.value;
        mCenterYType = d.type;
/*
        mCenterX = a.getFloat(com.android.internal.R.styleable.Rotate3dAnimation_centerX3d, 0.0f);
        mCenterY = a.getFloat(com.android.internal.R.styleable.Rotate3dAnimation_centerY3d, 0.0f);
    */
        mDepthZ = a.getFloat(com.android.internal.R.styleable.Rotate3dAnimation_depthZ, 0.0f);
        mReverse = a.getBoolean(com.android.internal.R.styleable.Rotate3dAnimation_reverse, false);

        a.recycle();
    }
    /**
     * Creates a new 3D rotation on the Y axis. The rotation is defined by its
     * start angle and its end angle. Both angles are in degrees. The rotation
     * is performed around a center point on the 2D space, definied by a pair
     * of X and Y coordinates, called centerX and centerY. When the animation
     * starts, a translation on the Z axis (depth) is performed. The length
     * of the translation can be specified, as well as whether the translation
     * should be reversed in time.
     *
     * @param fromDegrees the start angle of the 3D rotation
     * @param toDegrees the end angle of the 3D rotation
     * @param centerX the X center of the 3D rotation
     * @param centerY the Y center of the 3D rotation
     * @param reverse true if the translation should be reversed, false otherwise
     */
    public Rotate3dAnimation(float fromDegrees, float toDegrees,
            float centerX, float centerY, float depthZ, boolean reverse) {
        mFromDegrees = fromDegrees;
        mToDegrees = toDegrees;
        mCenterX = centerX;
        mCenterY = centerY;
        mDepthZ = depthZ;
        mReverse = reverse;
    }

    @Override
    public void initialize(int width, int height, int parentWidth, int parentHeight) {
        super.initialize(width, height, parentWidth, parentHeight);
        mCamera = new Camera();
        mCenterX = resolveSize(mCenterXType, mCenterXValue, width, parentWidth);
        mCenterY = resolveSize(mCenterYType, mCenterYValue, height, parentHeight);
    }

    @Override
    protected void applyTransformation(float interpolatedTime, Transformation t) {
        final float fromDegrees = mFromDegrees;
        float degrees = fromDegrees + ((mToDegrees - fromDegrees) * interpolatedTime);

        final float centerX = mCenterX;
        final float centerY = mCenterY;
        final Camera camera = mCamera;

        final Matrix matrix = t.getMatrix();

        camera.save();
        if (mReverse) {
            camera.translate(0.0f, 0.0f, mDepthZ * interpolatedTime);
        } else {
            camera.translate(0.0f, 0.0f, mDepthZ * (1.0f - interpolatedTime));
        }
        camera.rotateY(degrees);
        camera.getMatrix(matrix);
        camera.restore();

        matrix.preTranslate(-centerX, -centerY);
        matrix.postTranslate(centerX, centerY);
    }
}

