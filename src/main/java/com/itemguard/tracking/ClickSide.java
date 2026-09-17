package com.itemguard.tracking;

/** Which inventory in a click carries the container position, if any. */
public enum ClickSide {

    /** The inventory the player clicked in. */
    CLICKED,

    /** The other (top) inventory of the open view. */
    TOP,

    /** Neither: there is no block container in this click. */
    NONE
}
