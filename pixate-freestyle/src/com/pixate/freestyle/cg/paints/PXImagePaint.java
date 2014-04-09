/*******************************************************************************
 * Copyright 2012-present Pixate, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.pixate.freestyle.cg.paints;

import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.net.Uri;
import android.os.AsyncTask;

import com.pixate.freestyle.cg.parsing.PXSVGLoader;
import com.pixate.freestyle.cg.shapes.PXShapeDocument;
import com.pixate.freestyle.util.PXLog;
import com.pixate.freestyle.util.StringUtil;
import com.pixate.freestyle.util.UrlStreamOpener;

public class PXImagePaint extends BasePXPaint {
    private static final String TAG = PXImagePaint.class.getSimpleName();
    private static final Set<String> SUPPORTED_REMOTE_SCHEMES = new HashSet<String>(Arrays.asList(
            "http", "https", "ftp"));

    public enum PXImageRepeatType {
        REPEAT, SPACE, ROUND, NOREPEAT
    };

    private Uri imageURL;
    private AsyncTask<Uri, Void, Object> remoteImageLoader;

    public PXImagePaint(Uri imageURL) {
        this.imageURL = imageURL;
        String scheme = imageURL.getScheme();
        if (scheme != null && SUPPORTED_REMOTE_SCHEMES.contains(scheme.toLowerCase(Locale.US))) {
            // Start a FutureTask to load that image.
            // Note that this may require INTERNET permissions in the manifest.
            // <uses-permission android:name="android.permission.INTERNET" />
            // The returned value can be a Drawable, in case that the url is for
            // an image, and a PXShapeDocument in case the url represents a path
            // to an SVG resource.
            remoteImageLoader = new AsyncTask<Uri, Void, Object>() {
                @Override
                protected Object doInBackground(Uri... params) {
                    try {
                        if (hasSVGImageURL()) {
                            return PXSVGLoader.loadFromStream(UrlStreamOpener.open(params[0]
                                    .toString()));
                        } else {
                            return NinePatchDrawable.createFromStream(
                                    new URL(params[0].toString()).openStream(), null);
                        }
                    } catch (Exception e) {
                        PXLog.e(TAG, e, "Error while loading a remote image");
                    }
                    return null;
                }
            };
            remoteImageLoader.execute(imageURL);
        }
    }

    /*
     * (non-Javadoc)
     * @see com.pixate.freestyle.cg.paints.PXPaint#isOpaque()
     */
    public boolean isOpaque() {
        // TODO
        return true;
    }

    public boolean hasSVGImageURL() {
        if (imageURL == null) {
            return false;
        }
        String file = imageURL.getPath();
        String scheme = imageURL.getScheme();
        String resourceSpecifier = imageURL.getEncodedSchemeSpecificPart();
        return !StringUtil.isEmpty(file) && file.toLowerCase(Locale.US).endsWith(".svg")
                || !StringUtil.isEmpty(scheme) && !StringUtil.isEmpty(resourceSpecifier)
                && UrlStreamOpener.DATA_SCHEME.startsWith(scheme.toLowerCase(Locale.US))
                && resourceSpecifier.toLowerCase(Locale.US).startsWith("image/svg+xml");
    }

    public Uri getImageUrl() {
        return imageURL;
    }

    public Picture imageForBounds(Rect bounds) {
        Picture image = null;
        if (imageURL != null) {
            // create image
            try {
                image = new Picture();
                Canvas canvas = image.beginRecording(bounds.width(), bounds.height());
                if (hasSVGImageURL()) {
                    // for android, instead of using the PXShapeView (which
                    // requires a Context), we directly load the scene with
                    // PXSVGLoader.loadFromURL(URL)
                    PXShapeDocument document;
                    if (remoteImageLoader != null) {
                        document = (PXShapeDocument) remoteImageLoader.get();
                    } else {
                        document = PXSVGLoader.loadFromStream(UrlStreamOpener.open(imageURL));
                    }
                    document.setBounds(new RectF(bounds));
                    document.render(canvas);
                } else {
                    // read the data as a bitmap image
                    InputStream inputStream = null;
                    try {
                        Drawable d;
                        if (remoteImageLoader != null) {
                            d = (Drawable) remoteImageLoader.get();
                        } else {
                            inputStream = UrlStreamOpener.open(imageURL);
                            // Try to load this data as a NinePatchDrawable. The
                            // fallback here, in case the bitmap is not
                            // nine-patch, is BitmapDrawable. Also, when the png
                            // is loaded from the assets directory, we need to
                            // compile/encode it via the "aapt" tool first!
                            // Otherwise, it will not load the nine-patch chunk
                            // data.
                            d = NinePatchDrawable.createFromStream(inputStream, null);
                        }
                        if (d != null) {
                            d.setBounds(bounds);
                            d.draw(canvas);
                        }
                    } finally {
                        if (inputStream != null) {
                            inputStream.close();
                        }
                    }
                }
            } catch (Exception e) {
                PXLog.e(TAG, e, "Error loading a PXImagePaint");
            } finally {
                image.endRecording();
            }
            // TODO - is there an Android alternative?
            // scale = [basename hasSuffix:@"@2x"] ? 2.0f : 1.0f; // TODO: pull
            // out number and use that?
            //
            // // resize, if necessary
            // if (image && !CGSizeEqualToSize(image.size, size))
            // {
            // UIGraphicsBeginImageContextWithOptions(size, NO, 0.0);
            // [image drawInRect:CGRectMake(0, 0, size.width, size.height)];
            // image = UIGraphicsGetImageFromCurrentImageContext();
            // UIGraphicsEndImageContext();
        }
        return image;
    }

    public void applyFillToPath(Path path, Paint paint, Canvas context) {
        context.save();
        // clip to path
        context.clipPath(path);
        // do the gradient
        Rect bounds = new Rect();
        context.getClipBounds(bounds);
        Picture image = imageForBounds(bounds);
        // draw
        if (image != null) {
            // TODO - Blending mode? We may need to convert the Picture to a
            // Bitmap and then call drawBitmap
            context.drawPicture(image);
        }
        context.restore();
    }

    public PXPaint lightenByPercent(float percent) {
        // TODO
        return this;
    }

    public PXPaint darkenByPercent(float percent) {
        // TODO
        return this;
    }
}
