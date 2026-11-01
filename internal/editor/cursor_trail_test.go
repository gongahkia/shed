package editor

import (
	"math"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/gongahkia/shack/internal/text"
)

func trailApp(t *testing.T) (*App, time.Time) {
	t.Helper()
	a := testApp(t)
	a.Config.CursorTrail = true
	t.Cleanup(a.clearCursorTrail)
	a.frame.Resize(80, 24)
	for y := 0; y < 24; y++ {
		for x := 0; x < 80; x++ {
			a.frame.Put(x, y, "x", a.styles().text)
		}
	}
	a.trackCursor(5, 2, 0, rectangle{4, 1, 76, 20})
	now := time.Now()
	a.drawCursorTrail(now, a.styles())
	return a, now
}

func TestCursorTrailFadesRetargetsAndStops(t *testing.T) {
	a, now := trailApp(t)
	if a.cursorC != nil {
		t.Fatal("initial position started a timer")
	}
	a.cursorCandidate.x, a.cursorCandidate.offset = 45, 40
	a.drawCursorTrail(now, a.styles())
	if a.cursorC == nil || a.cursorTrail.started.IsZero() {
		t.Fatal("jump did not start animation")
	}
	_, tinted, _ := a.frame.Get(35, 2)
	if tinted == a.styles().text {
		t.Fatal("jump did not paint a trail")
	}
	half := now.Add(cursorTrailDuration / 2)
	x, y := a.cursorTrail.tail(half)
	if x <= 5 || x >= 45 || y != 2 {
		t.Fatal("trail did not contract toward destination", x, y)
	}
	a.cursorCandidate.x, a.cursorCandidate.offset = 15, 10
	a.drawCursorTrail(half, a.styles())
	if math.Abs(a.cursorTrail.fromX-x) > 0.001 || a.cursorTrail.fromY != y {
		t.Fatal("interrupted trail jumped back to previous caret")
	}
	a.drawCursorTrail(half.Add(cursorTrailDuration), a.styles())
	if a.cursorC != nil || !a.cursorTrail.started.IsZero() {
		t.Fatal("settled trail retained animation work")
	}
	a.drawCursorTrail(half.Add(time.Second), a.styles())
	if a.cursorC != nil {
		t.Fatal("idle redraw restarted animation")
	}
}

func TestCursorTrailClearsWhenContextChanges(t *testing.T) {
	for _, change := range []struct {
		name string
		run  func(*App)
	}{
		{"disabled", func(a *App) { a.Config.CursorTrail = false }},
		{"dialog", func(a *App) { a.Dialog = &dialog{} }},
		{"preview", func(a *App) { a.Preview = &preview{} }},
		{"menu", func(a *App) { a.Menu = &contextMenu{} }},
		{"tree focus", func(a *App) { a.treeFocus = true }},
		{"hidden caret", func(a *App) { a.cursorCandidate.valid = false }},
		{"edit", func(a *App) { a.cursorCandidate.view.version++ }},
		{"scroll", func(a *App) { a.cursorCandidate.view.top++ }},
		{"resize", func(a *App) { a.cursorCandidate.view.area.W-- }},
		{"pane", func(a *App) { a.cursorCandidate.view.pane++ }},
		{"buffer", func(a *App) { a.cursorCandidate.view.buffer = &Buffer{} }},
		{"adjacent move", func(a *App) { a.cursorCandidate.x++; a.cursorCandidate.offset++ }},
		{"wide adjacent move", func(a *App) { a.cursorCandidate.x += 2; a.cursorCandidate.offset += 3 }},
	} {
		t.Run(change.name, func(t *testing.T) {
			a, now := trailApp(t)
			a.cursorCandidate.x, a.cursorCandidate.offset = 45, 40
			a.drawCursorTrail(now, a.styles())
			change.run(a)
			a.drawCursorTrail(now.Add(time.Millisecond), a.styles())
			if a.cursorC != nil || !a.cursorTrail.started.IsZero() {
				t.Fatal("context change retained animation")
			}
		})
	}
}

func TestCursorTrailMouseUnicodeAndFrameRestoration(t *testing.T) {
	for _, theme := range []string{"dark", "light"} {
		t.Run(theme, func(t *testing.T) {
			a := testApp(t)
			a.Config.CursorTrail = true
			a.Config.Theme = theme
			t.Cleanup(a.clearCursorTrail)
			b := a.Current()
			source := strings.Repeat("界 é 👩‍💻 example words with room to click\n", 12)
			b.Doc, _ = text.NewDocument(source)
			a.draw()
			// A mouse press sets dragging even before the pointer moves. It
			// must still animate an ordinary click; a real selection cancels it.
			a.Handle(tcell.NewEventMouse(35, 6, tcell.Button1, 0))
			a.draw()
			if a.cursorTrail.started.IsZero() || a.cursorC == nil {
				t.Fatal("click did not animate")
			}
			x, y, visible := a.Screen.(tcell.SimulationScreen).GetCursor()
			if !visible || x != a.cursorCandidate.x || y != a.cursorCandidate.y {
				t.Fatal("real caret delayed or hidden", x, y, visible)
			}
			cells, _, _ := a.Screen.(tcell.SimulationScreen).GetContents()
			cells = append([]tcell.SimCell(nil), cells...)
			a.Handle(tcell.NewEventMouse(35, 6, tcell.ButtonNone, 0))
			// Force the final frame without waiting on wall-clock scheduling.
			a.cursorTrail.started = time.Now().Add(-time.Second)
			a.draw()
			after, _, _ := a.Screen.(tcell.SimulationScreen).GetContents()
			after = append([]tcell.SimCell(nil), after...)
			changed := 0
			for i := range cells {
				if !reflect.DeepEqual(cells[i].Runes, after[i].Runes) {
					t.Fatal("trail changed displayed graphemes", i)
				}
				if cells[i].Style != after[i].Style {
					changed++
				}
			}
			if changed == 0 || changed > cursorTrailCells*2 {
				t.Fatal("unexpected trail footprint", changed)
			}
			if b.Doc.Text.String() != source || b.Doc.Dirty() || b.Doc.Version != 0 || a.cursorC != nil {
				t.Fatal("trail affected document or retained timer")
			}
			// All overlay colors must be gone in the settled frame.
			a.Config.CursorTrail = false
			a.draw()
			plain, _, _ := a.Screen.(tcell.SimulationScreen).GetContents()
			if !reflect.DeepEqual(after, plain) {
				t.Fatal("settled frame differs from ordinary rendering")
			}
		})
	}
}

func TestCursorTrailWideGlyphAndHighlightPreservation(t *testing.T) {
	a, _ := trailApp(t)
	p := a.styles()
	a.frame.Put(10, 2, "界", p.text)
	a.frame.Put(14, 2, "é", p.text)
	a.frame.Put(20, 2, "x", p.selection)
	target := a.cursorCandidate
	target.x = 30
	a.paintCursorTrail(target, 11, 2, 0.6, p)
	value, style, width := a.frame.Get(10, 2)
	if value != "界" || width != 2 || style == p.text {
		t.Fatal("trail through wide continuation broke glyph", value, width, style)
	}
	value, _, _ = a.frame.Get(14, 2)
	if value != "é" {
		t.Fatal("trail broke combining cluster")
	}
	_, style, _ = a.frame.Get(20, 2)
	if style != p.selection {
		t.Fatal("trail overwrote highlight")
	}
	_, style, _ = a.frame.Get(3, 2)
	if style != p.text {
		t.Fatal("trail escaped text area")
	}
}
