//! CPU vector drawing and bundled, cached font rasterization. No display server.
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_precision_loss,
    clippy::cast_possible_wrap, // fixed framebuffer and bundled glyph sizes fit i32
    clippy::cast_sign_loss
)]
use super::{HEIGHT, WIDTH};
use fontdue::{Font, FontSettings, Metrics};
use std::collections::HashMap;
use tiny_skia::{FillRule, Mask, Paint, PathBuilder, Pixmap, Rect, Stroke, Transform};

pub const FONT_BYTES: &[u8] = include_bytes!("../../../../assets/fonts/Inter.ttf");

pub struct Canvas {
    pub pixels: Pixmap,
    clip: Mask,
    map_clip: bool,
    font: Font,
    glyphs: HashMap<(char, u16), (Metrics, Vec<u8>)>,
}

impl Canvas {
    pub fn new() -> Self {
        let mut clip = Mask::new(WIDTH as u32, HEIGHT as u32).expect("fixed replay clip");
        let [x, y, w, h] = super::scene::VIEWPORT;
        let path = PathBuilder::from_rect(Rect::from_xywh(x, y, w, h).unwrap());
        clip.fill_path(&path, FillRule::Winding, false, Transform::identity());
        Self {
            clip,
            map_clip: false,
            pixels: Pixmap::new(WIDTH as u32, HEIGHT as u32).expect("fixed replay dimensions"),
            font: Font::from_bytes(FONT_BYTES, FontSettings::default())
                .expect("bundled Inter font"),
            glyphs: HashMap::new(),
        }
    }
    pub fn map_clip(&mut self, enabled: bool) {
        self.map_clip = enabled;
    }
    pub fn clear(&mut self, color: u32) {
        self.pixels.fill(color_value(color));
    }
    pub fn rect(&mut self, rect: [f32; 4], color: u32) {
        if let Some(rect) = Rect::from_xywh(rect[0], rect[1], rect[2], rect[3]) {
            self.pixels.fill_rect(
                rect,
                &paint(color),
                Transform::identity(),
                self.map_clip.then_some(&self.clip),
            );
        }
    }
    pub fn polygon(
        &mut self,
        points: &[(f32, f32)],
        fill: Option<u32>,
        stroke: Option<(u32, f32)>,
    ) {
        let Some(&(x, y)) = points.first() else {
            return;
        };
        let mut path = PathBuilder::new();
        path.move_to(x, y);
        for &(x, y) in &points[1..] {
            path.line_to(x, y);
        }
        path.close();
        if let Some(path) = path.finish() {
            if let Some(color) = fill {
                self.pixels.fill_path(
                    &path,
                    &paint(color),
                    FillRule::Winding,
                    Transform::identity(),
                    self.map_clip.then_some(&self.clip),
                );
            }
            if let Some((color, width)) = stroke {
                self.pixels.stroke_path(
                    &path,
                    &paint(color),
                    &Stroke {
                        width,
                        ..Stroke::default()
                    },
                    Transform::identity(),
                    self.map_clip.then_some(&self.clip),
                );
            }
        }
    }
    pub fn line(&mut self, from: (f32, f32), to: (f32, f32), color: u32, width: f32) {
        let mut path = PathBuilder::new();
        path.move_to(from.0, from.1);
        path.line_to(to.0, to.1);
        if let Some(path) = path.finish() {
            self.pixels.stroke_path(
                &path,
                &paint(color),
                &Stroke {
                    width,
                    ..Stroke::default()
                },
                Transform::identity(),
                self.map_clip.then_some(&self.clip),
            );
        }
    }
    pub fn dashed(&mut self, from: (f32, f32), to: (f32, f32), color: u32) {
        let dx = to.0 - from.0;
        let dy = to.1 - from.1;
        let length = dx.hypot(dy);
        if !length.is_finite() || length <= 0.0 {
            return;
        }
        for n in 0..((length / 9.0).ceil() as u32).min(1000) {
            let a = n as f32 * 9.0 / length;
            let b = ((n as f32 * 9.0 + 4.0) / length).min(1.0);
            self.line(
                (from.0 + dx * a, from.1 + dy * a),
                (from.0 + dx * b, from.1 + dy * b),
                color,
                1.0,
            );
        }
    }
    pub fn circle(
        &mut self,
        at: (f32, f32),
        radius: f32,
        fill: Option<u32>,
        stroke: Option<(u32, f32)>,
    ) {
        let Some(path) = PathBuilder::from_circle(at.0, at.1, radius) else {
            return;
        };
        if let Some(color) = fill {
            self.pixels.fill_path(
                &path,
                &paint(color),
                FillRule::Winding,
                Transform::identity(),
                self.map_clip.then_some(&self.clip),
            );
        }
        if let Some((color, width)) = stroke {
            self.pixels.stroke_path(
                &path,
                &paint(color),
                &Stroke {
                    width,
                    ..Stroke::default()
                },
                Transform::identity(),
                self.map_clip.then_some(&self.clip),
            );
        }
    }
    fn display_char(&self, c: char) -> char {
        if c.is_control() {
            ' '
        } else if self.font.has_glyph(c) {
            c
        } else {
            '?'
        }
    }
    fn advance(&self, c: char, size: u16) -> f32 {
        // Tabular numbers keep changing timestamps and counts stable.
        self.font
            .metrics(
                if c.is_ascii_digit() {
                    '0'
                } else {
                    self.display_char(c)
                },
                f32::from(size),
            )
            .advance_width
    }
    pub fn text_width(&self, text: &str, size: u16) -> f32 {
        text.chars().map(|c| self.advance(c, size)).sum()
    }
    pub fn fit_text(&self, text: &str, size: u16, max_width: f32) -> String {
        if self.text_width(text, size) <= max_width {
            return text.to_owned();
        }
        let mut result = String::new();
        let mut width = self.text_width("…", size);
        for c in text.chars() {
            width += self.advance(c, size);
            if width > max_width {
                break;
            }
            result.push(c);
        }
        result.push('…');
        result
    }
    pub fn text(&mut self, at: (f32, f32), text: &str, size: u16, color: u32, max_width: f32) {
        let text = self.fit_text(text, size, max_width);
        let mut x = at.0;
        for c in text.chars() {
            let advance = self.advance(c, size);
            let c = self.display_char(c);
            if self.glyphs.len() > 2048 {
                self.glyphs.clear();
            }
            let (metrics, bitmap) = self
                .glyphs
                .entry((c, size))
                .or_insert_with(|| self.font.rasterize(c, f32::from(size)));
            let left = x.round() as i32 + metrics.xmin;
            let top = at.1.round() as i32 - metrics.height as i32 - metrics.ymin;
            let data = self.pixels.data_mut();
            for row in 0..metrics.height {
                let y = top + row as i32;
                if !(0..HEIGHT as i32).contains(&y) {
                    continue;
                }
                for column in 0..metrics.width {
                    let px = left + column as i32;
                    if !(0..WIDTH as i32).contains(&px) {
                        continue;
                    }
                    if self.map_clip && self.clip.data()[y as usize * WIDTH + px as usize] == 0 {
                        continue;
                    }
                    let alpha = u32::from(bitmap[row * metrics.width + column]);
                    if alpha == 0 {
                        continue;
                    }
                    let index = (y as usize * WIDTH + px as usize) * 4;
                    for (channel, shift) in [16, 8, 0].iter().enumerate() {
                        let foreground = (color >> shift) & 255;
                        data[index + channel] = ((foreground * alpha
                            + u32::from(data[index + channel]) * (255 - alpha)
                            + 127)
                            / 255) as u8;
                    }
                }
            }
            x += advance;
        }
    }
    pub fn copy_to(&self, buffer: &mut [u32]) {
        for (out, pixel) in buffer.iter_mut().zip(self.pixels.data().as_chunks::<4>().0) {
            *out = u32::from(pixel[0]) << 16 | u32::from(pixel[1]) << 8 | u32::from(pixel[2]);
        }
    }
}
fn color_value(color: u32) -> tiny_skia::Color {
    tiny_skia::Color::from_rgba8((color >> 16) as u8, (color >> 8) as u8, color as u8, 255)
}
fn paint(color: u32) -> Paint<'static> {
    let mut paint = Paint::default();
    paint.set_color(color_value(color));
    paint.anti_alias = true;
    paint
}
