package org.gignac.jp.pathological;
import android.graphics.*;

class Stoplight extends Tile {
	public int current;
	public int[] marbles;
	private static final int stoplight_marble_size = 28;

	public Stoplight(GameResources gr, String colors) {
		super(gr, 0); // Call base class intializer
		marbles = new int[3];
		for(int i=0; i<3; ++i)
			marbles[i] = colors.charAt(i)-'0';
		current = 0;
		Sprite.cache(R.drawable.stoplight);
	}

	@Override
	public void draw_back( Blitter b) {
		super.draw_back(b);
		b.blit( R.drawable.stoplight, pos.left, pos.top, tile_size, tile_size);
		for(int i=current; i < 3; ++i) {
			b.blit( Marble.marble_images[marbles[i]],
				pos.left + Tile.tile_size/2 - 14,
				pos.top + 3 + (29*i),
				stoplight_marble_size,
				stoplight_marble_size);
		}
	}

	public void complete( Board board) {
		for( int i=0; i<3; ++i) {
			if( marbles[i] >= 0) {
				marbles[i] = -1;
				break;
			}
		}
		current += 1;
		board.game.increase_score( 20);
	}
}
