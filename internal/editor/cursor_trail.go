package editor

import (
	"math"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/gongahkia/shack/internal/text"
)

const (
	cursorTrailDuration = 150 * time.Millisecond
	cursorTrailInterval = time.Second / 30
	cursorTrailCells    = 64
)

// The terminal caret always moves immediately. This is a temporary background
// overlay on the composed frame, never an edit or a replacement for a glyph.
type cursorView struct {
	buffer             *Buffer
	document           *text.Document
	version            uint64
	pane               int
	area               rectangle
	top, left, wrapTop int
	wrap               bool
}

type cursorTarget struct {
	view         cursorView
	x, y, offset int
	valid        bool
}

type cursorTrail struct {
	last         cursorTarget
	fromX, fromY float64
	started      time.Time
}

func (a *App) trackCursor(x, y, offset int, area rectangle) {
	if !a.Config.CursorTrail {
		return
	}
	b := a.Current()
	if b.Transient || b.Reading != nil || len(b.Doc.Selections) != 1 || b.Doc.Selections[0].Start() != b.Doc.Selections[0].End() {
		return
	}
	a.cursorCandidate = cursorTarget{
		view: cursorView{b, b.Doc, b.Doc.Version, a.focus, area, b.Top, b.Left, b.WrapTop, a.wraps(b)},
		x:    x, y: y, offset: offset, valid: true,
	}
}

func (a *App) clearCursorTrail() {
	if a.cursorTimer != nil {
		a.cursorTimer.Stop()
	}
	a.cursorC = nil
	a.cursorTrail = cursorTrail{}
}

// Retarget an interrupted trail from its current visual tail. No frames queue
// behind input, and timers exist only while a visible trail is fading.
func (s *cursorTrail) tail(now time.Time) (float64, float64) {
	progress := min(1.0, max(0.0, float64(now.Sub(s.started))/float64(cursorTrailDuration)))
	remaining := math.Pow(1-progress, 3)
	return float64(s.last.x) + (s.fromX-float64(s.last.x))*remaining,
		float64(s.last.y) + (s.fromY-float64(s.last.y))*remaining
}

func (a *App) drawCursorTrail(now time.Time, p palette) {
	current := a.cursorCandidate
	if !a.Config.CursorTrail || !current.valid || a.Dialog != nil || a.Preview != nil || a.Menu != nil || a.treeFocus || a.Screen.Colors() < 8 {
		a.clearCursorTrail()
		return
	}
	s := &a.cursorTrail
	last := s.last
	if !last.valid || last.view != current.view || (last.offset == current.offset && (last.x != current.x || last.y != current.y)) {
		a.clearCursorTrail()
		s.last = current
		return
	}
	if last.x != current.x || last.y != current.y {
		// Ordinary adjacent movement and typing do not create a trail. Document
		// changes, scrolling and layout changes reset the context above.
		if max(math.Abs(float64(last.x-current.x)), math.Abs(float64(last.y-current.y))) <= 2 {
			a.clearCursorTrail()
			s.last = current
			return
		}
		fromX, fromY := float64(last.x), float64(last.y)
		if !s.started.IsZero() && now.Sub(s.started) < cursorTrailDuration {
			fromX, fromY = s.tail(now)
		}
		s.fromX, s.fromY, s.started = fromX, fromY, now
	}
	s.last = current
	if s.started.IsZero() {
		return
	}
	elapsed := now.Sub(s.started)
	if elapsed >= cursorTrailDuration {
		a.clearCursorTrail()
		s.last = current
		return
	}
	strength := 0.6 * (1 - float64(elapsed)/float64(cursorTrailDuration))
	fromX, fromY := s.tail(now)
	a.paintCursorTrail(current, fromX, fromY, strength, p)
	if a.cursorC == nil {
		delay := min(cursorTrailInterval, cursorTrailDuration-elapsed)
		if a.cursorTimer == nil {
			a.cursorTimer = time.NewTimer(delay)
		} else {
			a.cursorTimer.Reset(delay)
		}
		a.cursorC = a.cursorTimer.C
	}
}

func (a *App) paintCursorTrail(target cursorTarget, fromX, fromY, strength float64, p palette) {
	type point struct{ x, y int }
	weights := make(map[point]float64, cursorTrailCells)
	dx, dy := fromX-float64(target.x), fromY-float64(target.y)
	distance := max(math.Abs(dx), math.Abs(dy))
	if distance > cursorTrailCells-1 {
		scale := float64(cursorTrailCells-1) / distance
		dx, dy = dx*scale, dy*scale
		distance = cursorTrailCells - 1
	}
	steps := max(1, int(math.Ceil(distance)))
	r := target.view.area
	for i := 0; i <= steps; i++ {
		u := float64(i) / float64(steps)
		pt := point{target.x + int(math.Round(dx*(1-u))), target.y + int(math.Round(dy*(1-u)))}
		if pt.x >= r.X && pt.x < r.X+r.W && pt.y >= r.Y && pt.y < r.Y+r.H && (pt.x != target.x || pt.y != target.y) {
			weights[pt] = max(weights[pt], strength*(0.3+0.7*u))
		}
	}
	accent, _, _ := p.accent.Decompose()
	_, baseBG, _ := p.text.Decompose()
	// Walk glyph starts so a trail through the second cell of a wide character
	// colors the whole character. Combining clusters and source text stay intact.
	for y := r.Y; y < r.Y+r.H; y++ {
		for x := r.X; x < r.X+r.W; {
			value, style, width := a.frame.Get(x, y)
			weight := weights[point{x, y}]
			if width == 2 {
				weight = max(weight, weights[point{x + 1, y}])
			}
			_, bg, _ := style.Decompose()
			// Preserve selections, search matches, debugger highlights and media.
			if weight > 0 && bg == baseBG && x+width <= r.X+r.W {
				ar, ag, ab := accent.RGB()
				br, bg, bb := bg.RGB()
				blend := func(base, tint int32) int32 { return base + int32(float64(tint-base)*weight) }
				style = style.Background(tcell.NewRGBColor(blend(br, ar), blend(bg, ag), blend(bb, ab)))
				a.frame.Put(x, y, value, style)
			}
			x += max(1, width)
		}
	}
}
