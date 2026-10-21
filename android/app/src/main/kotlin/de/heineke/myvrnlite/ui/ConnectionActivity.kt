package de.heineke.myvrnlite.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import de.heineke.myvrnlite.R
import de.heineke.myvrnlite.fahrplan
import org.osmdroid.util.GeoPoint

/** One connection from the Fahrplan results: the route on the map, the timeline in a bottom sheet. */
class ConnectionActivity : BaseActivity() {
    private lateinit var map: RouteMap
    private lateinit var sheet: BottomSheet
    private var points: List<GeoPoint> = emptyList()

    private val wide get() = resources.configuration.screenWidthDp >= 900

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent(R.layout.activity_connection)
        titleView.setText(R.string.title_connection)
        navButton.setImageResource(R.drawable.ic_arrow_back)
        navButton.contentDescription = getString(R.string.back)
        navButton.setOnClickListener { finish() }
        action.visibility = View.GONE

        // The results live in the app's Fahrplan state; after a process restart they're gone.
        val j = fahrplan.journeys.getOrNull(intent.getIntExtra(EXTRA_INDEX, -1))
        if (j == null) {
            Toast.makeText(this, R.string.fp_trip_gone, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        map = RouteMap(this, persistView = false)
        findViewById<FrameLayout>(R.id.map_container).addView(map.view)
        points = map.drawJourney(j)

        sheet = findViewById(R.id.sheet)
        val body = findViewById<LinearLayout>(R.id.sheet_body)
        val summary = journeySummary(j)
        body.add(summary)
        if (j.cancelled) body.add(alert(AlertKind.BAD, getString(R.string.fp_cancelled_detail)), top = 10)
        if (j.longDistance.isNotEmpty()) {
            body.add(alert(AlertKind.INFO, getString(R.string.fp_long_distance_detail, j.longDistance.joinToString(", "))), top = 10)
        }
        body.add(timeline(j), top = 10)
        body.add(hint(getString(R.string.fp_footer)).apply { gravity = Gravity.CENTER })
        sheet.peekView = summary

        if (wide) {
            sheet.side = true
            (sheet.layoutParams as FrameLayout.LayoutParams).apply {
                width = dp(minOf(420, maxOf(340, (resources.configuration.screenWidthDp * 0.34).toInt())))
                setMargins(dp(16), dp(16), dp(16), dp(16))
            }
            sheet.setBackgroundResource(R.drawable.bg_panel_round)
            findViewById<View>(R.id.sheet_grab).visibility = View.GONE
            body.setPadding(dp(14), dp(16), dp(14), dp(20))
        } else {
            sheet.set(BottomSheet.State.HALF, animate = false)
        }
        // Once the sheet has its height: fit the route into the map above / beside it.
        sheet.post { sheet.post { fit() } }
    }

    private fun fit() = map.fit(points, sheet.covered, if (wide) sheet.width + dp(16) else 0)

    override fun onResume() {
        super.onResume()
        if (::map.isInitialized) map.onResume()
    }

    override fun onPause() {
        if (::map.isInitialized) map.onPause()
        super.onPause()
    }

    companion object {
        private const val EXTRA_INDEX = "journey"

        fun intent(context: Context, index: Int): Intent =
            Intent(context, ConnectionActivity::class.java).putExtra(EXTRA_INDEX, index)
    }
}
