// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.data

/** A change the TV refused. [text] is one plain line for the snackbar; [details] is what the TV printed, for a problem report. */
class ActionFailed(text: UiText, val details: String) : UiTextException(text)
