package com.example.oreshik

import android.app.Dialog
import android.graphics.Color
import android.view.View

/** Leaves the rounded content layout as the only painted surface of a modal bottom sheet. */
internal object BottomSheetSurface {
    fun clearOuterSurfaces(dialog: Dialog, content: View) {
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.decorView.setBackgroundColor(Color.TRANSPARENT)
        }

        var ancestor = content.parent as? View
        while (ancestor != null) {
            ancestor.setBackgroundColor(Color.TRANSPARENT)
            ancestor = ancestor.parent as? View
        }
    }
}
