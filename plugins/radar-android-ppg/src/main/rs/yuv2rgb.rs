#pragma version(1)
#pragma rs java_package_name(com.vl.recordaf)
#pragma rs_fp_relaxed

rs_allocation gCurrentFrame;

uchar4 RS_KERNEL yuv2rgb(uint32_t x, uint32_t y) {
    // Read in pixel values from latest frame - YUV color space

    uchar4 pixel;
    pixel.r = rsGetElementAtYuv_uchar_Y(gCurrentFrame, x, y);
    pixel.g = rsGetElementAtYuv_uchar_U(gCurrentFrame, x, y);
    pixel.b = rsGetElementAtYuv_uchar_V(gCurrentFrame, x, y);
    pixel.a = 255;

    // Convert YUV to RGB, JFIF transform with fixed-point math
    // R = Y + 1.402 * (V - 128)
    // G = Y - 0.34414 * (U - 128) - 0.71414 * (V - 128)
    // B = Y + 1.772 * (U - 128)

    int4 rgb;
    rgb.r = pixel.r +
            pixel.b * 1436 / 1024 - 179;
    rgb.g = pixel.r -
            pixel.g * 46549 / 131072 + 44 -
            pixel.b * 93604 / 131072 + 91;
    rgb.b = pixel.r +
            pixel.g * 1814 / 1024 - 227;
    rgb.a = 255;

    // Write out merged HDR result
    return convert_uchar4(clamp(rgb, 0, 255));
}
