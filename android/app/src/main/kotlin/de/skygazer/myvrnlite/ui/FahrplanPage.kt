package de.skygazer.myvrnlite.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.skygazer.myvrnlite.R
import de.skygazer.myvrnlite.data.Dates
import de.skygazer.myvrnlite.fahrplan
import de.skygazer.myvrnlite.net.NearbyStop
import de.skygazer.myvrnlite.net.Place
import org.osmdroid.util.GeoPoint
import java.util.Calendar

/**
 * Fahrplan tab: the map fills the page, the search lives in a bottom sheet that can be dragged
 * up to a full page (side panel on wide screens). Long-press on the map opens a place card
 * to use that point (or a stop nearby) as origin or destination.
 */
class FahrplanPage(activity: MainActivity) : Page(activity, R.layout.page_fahrplan) {
    private val fp = activity.fahrplan
    private val map = RouteMap(activity)
    private val sheet: BottomSheet = view.findViewById(R.id.sheet)
    private val from: AutoCompleteTextView = view.findViewById(R.id.from)
    private val to: AutoCompleteTextView = view.findViewById(R.id.to)
    private val dateButton: Button = view.findViewById(R.id.date)
    private val timeButton: Button = view.findViewById(R.id.time)
    private val results: LinearLayout = view.findViewById(R.id.results)
    private val card: View = view.findViewById(R.id.place_card)
    private val main = Handler(Looper.getMainLooper())
    private val onChange: () -> Unit = { render() }

    /** What the place card shows: the pressed point, or a stop chosen from its list. */
    private var cardPlace: Place? = null
    private var cardPoint: Place? = null
    private var cardSeq = 0
    /** The search last shown, to notice a new one (then the sheet moves to HALF). */
    private var shownSearch = -1
    private var locating = false

    private val wide get() = activity.wide

    init {
        val a = activity
        view.findViewById<FrameLayout>(R.id.map_container).addView(map.view)
        map.restoreView()
        sheet.peekView = view.findViewById(R.id.search_form)
        map.onLongPress = { p -> showCard(p) }
        map.onTap = { if (card.visibility == View.VISIBLE) closeCard() }

        pin(view.findViewById(R.id.pin_from), "from")
        pin(view.findViewById(R.id.pin_to), "to")
        setupField(from, isFrom = true)
        setupField(to, isFrom = false)
        to.setOnEditorActionListener { _, id, _ -> (id == EditorInfo.IME_ACTION_SEARCH).also { if (it) search() } }

        view.findViewById<ImageButton>(R.id.locate).apply { tint(a.attrColor(R.attr.cTextMuted)); setOnClickListener { locate() } }
        view.findViewById<ImageButton>(R.id.swap).apply {
            tint(a.attrColor(R.attr.brandInk))
            setOnClickListener { swap() }
        }
        dateButton.setOnClickListener { pickDate() }
        timeButton.setOnClickListener { pickTime() }
        view.findViewById<View>(R.id.now).setOnClickListener {
            fp.time = null
            fp.arrive = false
            renderWhen()
        }
        view.findViewById<View>(R.id.search).setOnClickListener { search() }

        view.findViewById<ImageButton>(R.id.pc_close).apply { tint(a.attrColor(R.attr.cTextMuted)); setOnClickListener { closeCard() } }
        view.findViewById<View>(R.id.pc_from).setOnClickListener { useCard(isFrom = true) }
        view.findViewById<View>(R.id.pc_to).setOnClickListener { useCard(isFrom = false) }

        from.setText(fp.from?.name.orEmpty(), false)
        to.setText(fp.to?.name.orEmpty(), false)
        applyWide()
        renderWhen()
        showPins(fit = fp.journeys.isEmpty() && (fp.from != null || fp.to != null))
    }

    override fun onResume() {
        fp.addListener(onChange)
        map.onResume()
        render()
    }

    override fun onPause() {
        fp.removeListener(onChange)
        map.onPause()
    }

    // ---- Layout: bottom sheet (phone) or side panel (wide) -----------------------------

    private fun applyWide() {
        val a = activity
        sheet.side = wide
        val lp = sheet.layoutParams as FrameLayout.LayoutParams
        val cardLp = card.layoutParams as FrameLayout.LayoutParams
        if (wide) {
            val panel = a.dp(minOf(420, maxOf(340, (a.resources.configuration.screenWidthDp * 0.34).toInt())))
            lp.width = panel
            lp.setMargins(a.dp(16), a.dp(16), a.dp(16), a.dp(16))
            sheet.setBackgroundResource(R.drawable.bg_panel_round)
            view.findViewById<View>(R.id.sheet_grab).visibility = View.GONE
            view.findViewById<View>(R.id.sheet_body).setPadding(a.dp(14), a.dp(16), a.dp(14), a.dp(20))
            cardLp.width = a.dp(380)
            cardLp.gravity = Gravity.BOTTOM or Gravity.START
            cardLp.setMargins(panel + a.dp(32), 0, a.dp(16), a.dp(16))
            card.setBackgroundResource(R.drawable.bg_card_round)
        }
        sheet.layoutParams = lp
        card.layoutParams = cardLp
    }

    private val coveredLeft get() = if (wide) sheet.width + activity.dp(16) else 0

    // ---- Fields ------------------------------------------------------------------------

    private fun setupField(field: AutoCompleteTextView, isFrom: Boolean) {
        field.setAdapter(SuggestAdapter(activity))
        field.dropDownVerticalOffset = activity.dp(2)
        field.setOnItemClickListener { parent, _, position, _ ->
            choose(parent.getItemAtPosition(position) as Place, isFrom, fromList = true)
        }
        field.setOnFocusChangeListener { _, focused -> if (focused && !wide) sheet.set(BottomSheet.State.FULL) }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                // Typed text no longer matches the chosen place: drop its pin (the search resolves the text).
                val chosen = if (isFrom) fp.from else fp.to
                if (chosen != null && chosen.name != s.toString()) map.setPin(if (isFrom) "from" else "to", null, null)
            }
        })
    }

    /** Use [place] as origin or destination. */
    private fun choose(place: Place, isFrom: Boolean, fromList: Boolean) {
        val field = if (isFrom) from else to
        val other = if (isFrom) to else from
        if (isFrom) fp.from = place else fp.to = place
        field.setText(place.name, false)
        field.dismissDropDown()
        map.setPin(if (isFrom) "from" else "to", place.lat ?: place.coordOf()?.first, place.lon ?: place.coordOf()?.second)
        if (fromList && other.text.isBlank()) {
            other.requestFocus()
            return
        }
        hideKeyboard()
        if (!wide) sheet.set(BottomSheet.State.PEEK)
        main.postDelayed({ fitPins() }, 260)
    }

    private fun swap() {
        val f = fp.from
        val t = fp.to
        val ft = from.text.toString()
        fp.from = t
        fp.to = f
        from.setText(to.text.toString(), false)
        to.setText(ft, false)
        from.dismissDropDown()
        to.dismissDropDown()
        showPins(fit = false)
    }

    private fun search() {
        hideKeyboard()
        from.dismissDropDown()
        to.dismissDropDown()
        fp.search(from.text.toString().trim(), to.text.toString().trim())
    }

    private fun hideKeyboard() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        from.clearFocus()
        to.clearFocus()
    }

    /** Small origin/destination markers left of the fields (ring and square, as on the map). */
    private fun pin(v: View, kind: String) {
        val c = activity.attrColor(R.attr.brandInk)
        v.background = GradientDrawable().apply {
            if (kind == "to") { shape = GradientDrawable.RECTANGLE; cornerRadius = activity.dp(2).toFloat(); setColor(c) }
            else { shape = GradientDrawable.OVAL; setStroke(activity.dp(3), c) }
        }
    }

    private fun showPins(fit: Boolean) {
        val f = fp.from
        val t = fp.to
        map.setPin("from", f?.lat ?: f?.coordOf()?.first, f?.lon ?: f?.coordOf()?.second)
        map.setPin("to", t?.lat ?: t?.coordOf()?.first, t?.lon ?: t?.coordOf()?.second)
        if (fit) sheet.post { fitPins() }
    }

    private fun fitPins() {
        val pts = map.pinPoints()
        if (pts.isNotEmpty()) map.fit(pts, sheet.covered, coveredLeft, maxZoom = 15.0)
    }

    // ---- Time --------------------------------------------------------------------------

    private fun renderWhen() {
        val a = activity
        view.findViewById<FrameLayout>(R.id.when_seg).apply {
            removeAllViews()
            addView(a.segmented(listOf("dep" to a.getString(R.string.fp_dep), "arr" to a.getString(R.string.fp_arr)),
                if (fp.arrive) "arr" else "dep") { fp.arrive = it == "arr"; renderWhen() })
        }
        val t = fp.time ?: System.currentTimeMillis()
        dateButton.text = Dates.format(t, false)
        timeButton.text = hm(t)
    }

    private fun calendar() = Calendar.getInstance(de.skygazer.myvrnlite.data.BERLIN).apply { timeInMillis = fp.time ?: System.currentTimeMillis() }

    private fun pickDate() {
        val c = calendar()
        DatePickerDialog(activity, { _, y, m, d ->
            c.set(y, m, d)
            fp.time = c.timeInMillis
            renderWhen()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickTime() {
        val c = calendar()
        TimePickerDialog(activity, { _, h, m ->
            c.set(Calendar.HOUR_OF_DAY, h)
            c.set(Calendar.MINUTE, m)
            c.set(Calendar.SECOND, 0)
            fp.time = c.timeInMillis
            renderWhen()
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }

    // ---- Results -------------------------------------------------------------------------

    override fun render() {
        val a = activity
        view.findViewById<Button>(R.id.search).apply {
            isEnabled = !fp.loading
            alpha = if (fp.loading) 0.6f else 1f
            setText(if (fp.loading) R.string.fp_searching else R.string.fp_search)
        }
        results.removeAllViews()
        fp.error?.let { results.add(a.alert(AlertKind.BAD, it), top = 12) }
        val list = fp.journeys
        if (list.isEmpty()) {
            renderRecent()
            return
        }
        if (fp.from?.name != from.text.toString() && !from.hasFocus()) from.setText(fp.from?.name.orEmpty(), false)
        if (fp.to?.name != to.text.toString() && !to.hasFocus()) to.setText(fp.to?.name.orEmpty(), false)

        val head = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        val headText = a.getString(if (fp.searchedArrive) R.string.fp_head_arr else R.string.fp_head_dep,
            hm(fp.searchedTime), Dates.format(list.first().dep ?: fp.searchedTime, false))
        head.addView(a.section(headText), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(shiftLink(R.string.fp_earlier, R.drawable.ic_chevron_up, -1))
        results.add(head)
        list.forEachIndexed { i, j ->
            results.add(a.journeyRow(j) { a.startActivity(ConnectionActivity.intent(a, i)) }, top = if (i == 0) 0 else 8)
        }
        results.add(shiftLink(R.string.fp_later, R.drawable.ic_chevron_down, +1), top = 6, width = ViewGroup.LayoutParams.WRAP_CONTENT)
        results.add(a.hint(a.getString(R.string.fp_footer)).apply { gravity = Gravity.CENTER })

        if (shownSearch != fp.searchId) {
            // A new search: show the results, and origin + destination on the map.
            shownSearch = fp.searchId
            if (!wide) sheet.set(BottomSheet.State.HALF)
            val o = list.first().depPoint
            val d = list.first().arrPoint
            map.setPin("from", o.lat, o.lon)
            map.setPin("to", d.lat, d.lon)
            main.postDelayed({ fitPins() }, 280)
        }
    }

    private fun shiftLink(label: Int, icon: Int, direction: Int): View {
        val a = activity
        val busy = fp.loadingMore == direction
        return LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(4), a.dp(8), a.dp(4), a.dp(8))
            addView(android.widget.ImageView(a).apply { setImageResource(icon); tint(a.attrColor(R.attr.brandInk)) },
                LinearLayout.LayoutParams(a.dp(20), a.dp(20)))
            addView(a.styled(R.style.Text, a.getString(label)).apply { setTextColor(a.attrColor(R.attr.brandInk)) })
            alpha = if (busy) 0.5f else 1f
            setOnClickListener { fp.more(direction) }
        }
    }

    private fun renderRecent() {
        val a = activity
        val recent = fp.recent
        if (recent.isNotEmpty()) {
            results.add(a.section(a.getString(R.string.fp_recent)))
            val chips = FlowLayout(a).apply { hGap = a.dp(8); vGap = a.dp(8) }
            for ((o, d) in recent) {
                chips.addView(a.styled(R.style.Text_Heading, "${o.name} → ${d.name}").apply {
                    textSize = 13f
                    setBackgroundResource(R.drawable.bg_chip)
                    setPadding(a.dp(12), a.dp(7), a.dp(12), a.dp(7))
                    setOnClickListener {
                        fp.from = o
                        fp.to = d
                        from.setText(o.name, false)
                        to.setText(d.name, false)
                        from.dismissDropDown()
                        to.dismissDropDown()
                        search()
                    }
                })
            }
            chips.addView(Button(a, null, 0, R.style.Button_Small_Outline).apply {
                setText(R.string.fp_recent_clear)
                setOnClickListener { fp.clearRecent() }
            })
            results.add(chips)
        }
        results.add(a.hint(a.getString(R.string.fp_tip)).apply { gravity = Gravity.CENTER; setPadding(a.dp(8), a.dp(18), a.dp(8), 0) })
    }

    // ---- Long-press place card ---------------------------------------------------------------

    private fun showCard(p: GeoPoint) {
        val a = activity
        val mine = ++cardSeq
        val point = Place(Place.coordId(p.latitude, p.longitude), a.getString(R.string.fp_place_loading),
            a.getString(R.string.fp_place_kind), p.latitude, p.longitude)
        cardPoint = point
        if (!wide) sheet.set(BottomSheet.State.PEEK)
        hideKeyboard()
        card.visibility = View.VISIBLE
        selectCard(point)
        view.findViewById<View>(R.id.pc_near_title).visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.pc_stops).removeAllViews()
        card.post { map.reveal(p, if (wide) 0 else card.height) }
        fp.here(p.latitude, p.longitude) { r ->
            if (mine != cardSeq || card.visibility != View.VISIBLE) return@here
            val (place, stops) = r.getOrNull() ?: (null to emptyList())
            val named = Place(point.id, place?.name ?: "Punkt auf der Karte", point.kind, point.lat, point.lon)
            cardPoint = named
            if (cardPlace === point) selectCard(named)
            showNearby(stops)
        }
    }

    private fun selectCard(place: Place) {
        cardPlace = place
        view.findViewById<TextView>(R.id.pc_kind).text = place.kind
        view.findViewById<TextView>(R.id.pc_name).text = place.name
        val lat = place.lat ?: return
        map.setPin("pick", lat, place.lon)
        val list = view.findViewById<LinearLayout>(R.id.pc_stops)
        for (i in 0 until list.childCount) list.getChildAt(i).isSelected = list.getChildAt(i).tag == place.id
    }

    private fun showNearby(stops: List<NearbyStop>) {
        val a = activity
        val list = view.findViewById<LinearLayout>(R.id.pc_stops)
        list.removeAllViews()
        view.findViewById<View>(R.id.pc_near_title).visibility = if (stops.isEmpty()) View.GONE else View.VISIBLE
        for (s in stops) {
            list.addView(LinearLayout(a).apply {
                tag = s.place.id
                setBackgroundResource(R.drawable.bg_nav_row)
                setPadding(a.dp(6), a.dp(9), a.dp(6), a.dp(9))
                addView(a.styled(R.style.Text_Heading, s.place.name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                s.distance?.let { addView(a.styled(R.style.Text_Hint, a.getString(R.string.fp_distance, it))) }
                setOnClickListener {
                    // Tap a stop to use it instead of the point; tap again to go back to the point.
                    if (cardPlace?.id == s.place.id) cardPoint?.let(::selectCard) else selectCard(s.place)
                }
            })
        }
    }

    private fun useCard(isFrom: Boolean) {
        val place = cardPlace ?: return
        if (place.name == activity.getString(R.string.fp_place_loading)) return  // still looking up the address
        closeCard()
        choose(place, isFrom, fromList = false)
        // Both ends set: search right away.
        if (fp.from != null && fp.to != null && from.text.isNotBlank() && to.text.isNotBlank()) search()
    }

    private fun closeCard() {
        cardSeq++
        card.visibility = View.GONE
        cardPlace = null
        map.setPin("pick", null, null)
    }

    // ---- Mein Standort -------------------------------------------------------------------

    private fun locate() {
        val a = activity
        if (Build.VERSION.SDK_INT >= 23 && a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), MainActivity.REQ_LOCATION)
            return
        }
        findLocation()
    }

    override fun onPermissionResult(granted: Boolean) {
        if (granted) findLocation() else Toast.makeText(activity, R.string.fp_location_denied, Toast.LENGTH_LONG).show()
    }

    @SuppressLint("MissingPermission")  // checked in locate()
    private fun findLocation() {
        if (locating) return
        val a = activity
        val lm = a.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            Toast.makeText(a, R.string.fp_location_off, Toast.LENGTH_LONG).show()
            return
        }
        val now = System.currentTimeMillis()
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.time < 2 * 60_000 }.maxByOrNull { it.time }
        if (recent != null) return useLocation(recent)

        locating = true
        Toast.makeText(a, R.string.fp_locating, Toast.LENGTH_SHORT).show()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!locating) return
                locating = false
                lm.removeUpdates(this)
                useLocation(location)
            }
            // Abstract before Android 10.
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        for (p in providers) {
            @Suppress("DEPRECATION")
            runCatching { lm.requestSingleUpdate(p, listener, Looper.getMainLooper()) }
        }
        main.postDelayed({
            if (!locating) return@postDelayed
            locating = false
            lm.removeUpdates(listener)
            // No fix in time: an older position is better than none.
            val old = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            if (old != null) useLocation(old) else Toast.makeText(a, R.string.fp_location_off, Toast.LENGTH_LONG).show()
        }, 15_000)
    }

    private fun useLocation(l: Location) {
        val place = Place(Place.coordId(l.latitude, l.longitude), activity.getString(R.string.fp_my_location), "", l.latitude, l.longitude)
        choose(place, isFrom = true, fromList = true)
        main.post { map.fit(listOf(GeoPoint(l.latitude, l.longitude)), sheet.covered, coveredLeft, maxZoom = 15.0) }
    }

    // ---- Suggestions ---------------------------------------------------------------------

    /** Stop-finder suggestions; AutoCompleteTextView filters on a background thread. */
    private inner class SuggestAdapter(context: Context) : BaseAdapter(), android.widget.Filterable {
        private var items: List<Place> = emptyList()
        private val ctx = context

        override fun getCount() = items.size
        override fun getItem(position: Int): Place = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val p = items[position]
            return LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(ctx.attrColor(R.attr.cSurface))
                setPadding(ctx.dp(12), ctx.dp(11), ctx.dp(12), ctx.dp(11))
                addView(ctx.styled(R.style.Text_Heading, p.name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ctx.styled(R.style.Text_Hint, p.kind).apply { setPadding(ctx.dp(8), 0, 0, 0) })
            }
        }

        override fun getFilter() = object : android.widget.Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val text = constraint?.toString()?.trim().orEmpty()
                val found = if (text.length < 2) emptyList() else fp.suggest(text)
                return FilterResults().apply { values = found; count = found.size }
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                items = (results?.values as? List<Place>).orEmpty()
                if (items.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
            }

            override fun convertResultToString(resultValue: Any?) = (resultValue as? Place)?.name ?: ""
        }
    }
}
