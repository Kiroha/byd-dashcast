.pragma library

// Measure descendants in the lamp's own coordinates. In particular, a native
// seat container may have zero dimensions while its grandchildren draw seats.
// Mapping a descendant to this ancestor excludes the lamp's own Translate.
function nativeBounds(lamp) {
    var left = Infinity, top = Infinity, right = -Infinity, bottom = -Infinity;
    var count = 0, valid = true;
    function visit(item, depth) {
        if (depth > 16 || ++count > 256) { valid = false; return; }
        // Explicit reads keep coordinate/scale changes in QML's binding graph.
        var geometry = [item.x, item.y, item.width, item.height, item.scale, item.rotation];
        for (var n = 0; n < geometry.length; ++n) if (!isFinite(geometry[n])) valid = false;
        if (item.width > 0 && item.height > 0) {
            var rect = item.mapToItem(lamp, 0, 0, item.width, item.height);
            left = Math.min(left, rect.x); top = Math.min(top, rect.y);
            right = Math.max(right, rect.x + rect.width); bottom = Math.max(bottom, rect.y + rect.height);
        }
        for (var i = 0; i < item.children.length; ++i) visit(item.children[i], depth + 1);
    }
    for (var i = 0; i < lamp.children.length; ++i) visit(lamp.children[i], 0);
    if (!valid || !isFinite(left) || !isFinite(top)) return { x: 0, y: 0, width: 0, height: 0 };
    return { x: left, y: top, width: right - left, height: bottom - top };
}

// Layout is based on native displayState, never the blink phase's visibility.
// Unknown states reserve space. Native seat layouts and image bounds count in
// their entirety; an item that cannot fit causes the stock layout to remain.
function pack(items, bounds, states) {
    var result = { fits: true, positions: [] };
    var x = 32, y = 88, rowHeight = 0;
    for (var i = 0; i < items.length; ++i) {
        var present = states[i];
        if (!present) { result.positions.push({ x: 0, y: 0 }); continue; }
        var b = bounds[i];
        var w = Math.max(1, b.width), h = Math.max(1, b.height);
        if (!isFinite(w) || !isFinite(h) || b.width <= 0 || b.height <= 0 || w > 1856) {
            result.fits = false;
        }
        if (x + w > 1888) { x = 32; y += rowHeight + 8; rowHeight = 0; }
        result.positions.push({ x: x, y: y });
        x += w + 14; rowHeight = Math.max(rowHeight, h);
        if (y + h > 240) result.fits = false;
    }
    return result;
}
