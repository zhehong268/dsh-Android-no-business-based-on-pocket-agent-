package dev.pocketagent.device

/** A platform-free copy of one accessibility node, as read from the live tree. */
data class RawNode(
    val className: String?,
    val text: String?,
    val description: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val isPassword: Boolean,
    val visibleToUser: Boolean,
    val packageName: String,
    val bounds: Bounds,
    val children: List<RawNode>,
    val checkable: Boolean = false,
    val checkedReliable: Boolean = true,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val stateDescription: String? = null,
    val viewId: String? = null,
    val hint: String? = null,
)
