package dev.linuxlab.helper;

/** Where the Linux screen is on the Android screen right now. */
interface Mapper {
    /** Screen coordinates to X root coordinates, packed as (x << 16) | y; -1 when unknown. */
    long toRoot(float rawX, float rawY);

    /** X root pixels per screen pixel; 0 when unknown. */
    float scale();

    int rootWidth();

    int rootHeight();
}
