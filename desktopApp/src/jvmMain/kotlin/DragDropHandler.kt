package net.morsecode.desktop

import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File

/**
 * Native drag-and-drop of files onto the window (Section D).
 *
 * Uses AWT's DropTarget on the Compose window's underlying AWT component, which
 * is the supported interop path for desktop drag-drop. Dropped files are handed
 * to [onFiles] so they can be queued into the send pipeline.
 */
object DragDropHandler {

    fun install(awtWindow: java.awt.Window, onFiles: (List<File>) -> Unit) {
        runCatching {
            DropTarget(
                awtWindow,
                DnDConstants.ACTION_COPY,
                object : DropTargetAdapter() {
                    override fun drop(evt: DropTargetDropEvent) {
                        runCatching {
                            evt.acceptDrop(DnDConstants.ACTION_COPY)
                            val transferable = evt.transferable
                            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                @Suppress("UNCHECKED_CAST")
                                val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                                onFiles(files)
                            }
                            evt.dropComplete(true)
                        }
                    }
                },
                true,
            )
        }
    }
}
