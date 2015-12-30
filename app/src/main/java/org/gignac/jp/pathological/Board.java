package org.gignac.jp.pathological;
import java.io.*;
import java.util.*;
import android.graphics.*;
import android.os.*;

class Board implements Paintable
{
    public static final int INCOMPLETE = 0;
    public static final int COMPLETE = 1;
    public static final int LAUNCH_TIMEOUT = -1;
    public static final int BOARD_TIMEOUT = -2;
    private static final String default_colors = "2346";
    private static final String default_stoplight = "643";
    private static final int default_launch_timer = 6;
    private static final int default_board_timer = 30;
    public static final int vert_tiles = 6;
    public static final int horiz_tiles = 8;
    public static final int board_width = horiz_tiles * Tile.tile_size;
    public static final int board_height = vert_tiles * Tile.tile_size;
    public static final int screen_width = board_width + Marble.marble_size;
    public static final int screen_height = board_height + Marble.marble_size;
    private int timer_width = Marble.marble_size*3/4;
    public GameResources gr;
    public Trigger trigger;
    public Stoplight stoplight;
    public Vector<Marble> marbles;
    public Tile[][] tiles;
    private int[] launch_queue;
    private int board_state;
    private boolean paused;
    public String name;
    public int live_marbles_limit;
    private int launch_timeout;
    private int launch_timeout_start;
    private int board_timeout;
    private int board_timeout_start;
    public String colors;
    public int launch_timer;
    private Marble[] marblesCopy = new Marble[20];
    private HashMap<Integer,Point> down;
    private float launch_queue_offset;
    private Bitmap liveCounter;
    private Canvas liveCounterCanvas;
    private final Paint paint = new Paint();
    private float scale = 0f;
    private float offsetx;
    private Runnable onPainted;
    public SpriteCache sc;
    private long pause_changed;
    private boolean dirty = true;
    public int delay = 50;

    public Board(GameResources gr, SpriteCache sc,
        int level, Runnable onPainted, boolean showTimer)
    {
        this.gr = gr;
        this.marbles = new Vector<Marble>();
        this.trigger = null;
        this.stoplight = null;
        this.launch_queue = new int[screen_height * 3 / Marble.marble_size];
        this.board_state = INCOMPLETE;
        this.paused = false;
        this.live_marbles_limit = 10;
        this.launch_timeout = -1;
        this.board_timeout = -1;
        this.colors = default_colors;
        this.onPainted = onPainted;
        this.sc = sc;
        this.pause_changed = SystemClock.uptimeMillis()-10000;
        if(!showTimer) timer_width = 0;

        down = new HashMap<Integer,Point>();

        paint.setAntiAlias(true);
        paint.setColor(0xfff0f0f0);
        paint.setTextSize(Marble.marble_size*4/5);
        paint.setTypeface(Typeface.DEFAULT_BOLD);

        // Seed the randomness based on the level number and
        // the current time.  Only use the time accurate to
        // the ten-minute interval.  This will discourage players
        // from reloading levels repeatedly in order to get
        // their choice of marbles/trigger/etc.
        gr.random.setSeed((System.currentTimeMillis()/600000)*1000+level);

        set_launch_timer( default_launch_timer);
        set_board_timer( default_board_timer);

        // Create the board array
        tiles = new Tile[vert_tiles][];
        for( int j=0; j < vert_tiles; ++j)
            tiles[j] = new Tile[horiz_tiles];

        // Prepare the live marbles counter image
        liveCounter = sc.getBitmap(0x100000002l);
        if( liveCounter == null) {
            liveCounter = Bitmap.createBitmap(
                SpriteCache.powerOfTwo(Marble.marble_size * 5),
                SpriteCache.powerOfTwo(Marble.marble_size),
                Bitmap.Config.ARGB_8888);
            sc.cache( 0x100000002l, liveCounter);
        }
        liveCounterCanvas = new Canvas(liveCounter);

        sc.cache( R.drawable.backdrop);
        sc.cache( R.drawable.misc);

        // Load the level
        try {
            _load(gr,  level);
        } catch(IOException e) {}

        // Fill up the launch queue
        for( int i=0; i < launch_queue.length; ++i) {
            launch_queue[i] = colors.charAt(gr.random.nextInt(colors.length()))-'0';
        }
    }

    private void draw_backdrop(Blitter b) {
        b.blit( R.drawable.backdrop,
            0, 0, b.getWidth(), b.getHeight());
    }

    private void draw_back(Blitter b)
    {
        // Black-out the right edge of the backdrop
        b.fill( 0xff000000, screen_width - Marble.marble_size*3/4,
            0, Marble.marble_size*3/4+timer_width, screen_height*2);

        // Draw the launcher
        b.blit( R.drawable.misc, 415, 394, 1, 30,
            28, -1, board_width-28, 30);
        b.blit( R.drawable.misc, 54, 394, 38, 30, 0, -1);
        b.blit(R.drawable.misc, 192, 387, 30, 1,
            board_width-1, 30, 30, board_height*3);
        b.blit( R.drawable.misc, 0, 440, 38, 38,
            board_width-9, -1);

        for( Tile[] row : tiles)
            for( Tile tile : row)
                tile.draw_back(b);
    }

    private void draw_mid( Blitter b)
    {
        int fullHeight = (int)Math.ceil(b.getHeight()/scale);

        // Draw the launch timer
        int timerColor = 0x40404040;
        float timeLeft = (float)launch_timeout / Game.frames_per_sec;
        if( timeLeft < 3.5f) {
            // Make the timer flash to indicate that time
            // is running out.
            float s = (float)Math.sin(timeLeft*5);
            int phase = Math.round(s*s*191);
            timerColor = 0x40404040 + (phase<<16) + ((phase*2/3)<<24);
        }
        int x = (launch_timeout*board_width+launch_timeout_start/2) /
            launch_timeout_start;
        b.fill(timerColor, x, 0,
            board_width - x, Marble.marble_size);

        // Draw the live marble counter
        b.blit(0x100000002l, Marble.marble_size/2, 0);

        // Draw the board timer
        timerColor = 0xff000080;
        timeLeft = (float)board_timeout / Game.frames_per_sec;
        if( timeLeft < 60f && board_timeout*2 < board_timeout_start) {
            // Make the timer flash to indicate that time
            // is running out.
            float s = (float)Math.sin(timeLeft*3);
            int phase = Math.round(s*s*255);
            timerColor = 0xff000000 | phase | (255-phase)<<16;
        }
        int timer_height = fullHeight;
        int y = (board_timeout*timer_height+board_timeout_start/2) /
            board_timeout_start;
        b.fill(0xff000000, screen_width+3,
            0, timer_width-3, timer_height-y);
        b.fill(timerColor, screen_width+3,
            timer_height-y, timer_width-3, y);

        // Draw the marble queue
//        b.fill(0xff000000, board_width, launch_queue_offset, 28, launch_queue.length*28);
        int iOffset = Math.round(launch_queue_offset);
        for(int i=0; i < launch_queue.length; ++i)
            b.blit(R.drawable.misc, 28*launch_queue[i], 357, 28, 28,
                board_width, iOffset + i * Marble.marble_size);
    }

    private void draw_fore( Blitter b) {
        for(Tile[] row : tiles)
            for(Tile tile : row)
                tile.draw_fore(b);

        drawPauseButton(b);
    }

    private void drawPauseButton(Blitter b)
    {
        if(board_state != INCOMPLETE) return;
        int intensity = (int)((SystemClock.uptimeMillis() - pause_changed) / 2);
        if( intensity > 255) intensity = 255;
        if(!paused) intensity ^= 0xff;
        if(intensity == 0) return;

        int borderColor = ((intensity/2)<<24)|0x000000;
        int color = (intensity<<24)|0xd0d0d0;

        int thickness = b.getWidth()/30;
        int spacing = thickness * 4/5;
        int height = thickness * 4;
        int x = ((int)(b.getWidth()/scale) -
            2*thickness - spacing) / 2 - (int)(offsetx/scale);
        int y = ((int)(b.getHeight()/scale) - height) / 2;
        b.fill(borderColor, (int)Math.floor(-offsetx/scale), 0,
            (int)(b.getWidth()/scale)+2, (int)(b.getHeight()/scale)+2);
        b.fill(color, x, y, thickness, height);
        b.fill(color, x+thickness+spacing, y,
               thickness, height);
    }

    public synchronized int update()
    {
        // Return INCOMPLETE even if the board is complete.
        // This ensures that the end of level signal is only
        // sent once.
        if(paused || board_state != INCOMPLETE) return INCOMPLETE;

        // Animate the marbles
        marblesCopy = marbles.toArray(marblesCopy);
        for(Marble marble : marblesCopy) {
            if( marble == null) break;
            marble.update(this);
        }

        // Animate the tiles
        for(Tile[] row : tiles)
            for(Tile tile : row)
                tile.update(this);

        // Complete any wheels, if appropriate
        boolean try_again = true;
        while(try_again) {
            try_again = false;
            for(Tile[] row : tiles)
                for(Tile tile : row)
                    if( tile instanceof Wheel)
                        try_again |= ((Wheel)tile).maybe_complete(this);
        }

        // Check if the board is complete
        board_state = COMPLETE;
        for(Tile[] row : tiles)
            for(Tile tile : row)
                if(tile instanceof Wheel)
                    if(!((Wheel)tile).completed)
                        board_state = INCOMPLETE;

        // Decrement the launch timer
        if(board_state == INCOMPLETE && launch_timeout > 0) {
            launch_timeout -= 1;
            if(launch_timeout == 0) board_state = LAUNCH_TIMEOUT;
        }

        // Decrement the board timer
        if( board_state == INCOMPLETE && board_timeout > 0) {
            board_timeout -= 1;
            if(board_timeout == 0) board_state = BOARD_TIMEOUT;
        }

        // Animate the launch queue
        if( launch_queue_offset > 0) {
            float speed = Marble.marble_speed*0.2f;  // Nice and slow
            // If we expect the marble to drop into the
            // top-right tile, accelerate the animation so
            // the launch queue doesn't have to jump.  But
            // wait for 10% of the marble height before we
            // accelerate, so the marbles don't appear to
            // overlap.
            Tile topRight = tiles[0][horiz_tiles-1];
            if( launch_queue_offset < Marble.marble_size * 0.9f &&
                (topRight.paths & 1) == 1 &&
               (!(topRight instanceof Wheel) ||
                ((Wheel)topRight).marbles[0] < 0))
                speed = Marble.marble_speed*0.7f;
            launch_queue_offset -= speed;
            if(launch_queue_offset < 0) launch_queue_offset = 0;
        }

        return board_state;
    }

    private void refresh_bg_cache()
    {
        // Refresh the background
        boolean dirty = false;
        for( Tile[] row : tiles) {
            for( Tile tile : row) {
                if( tile.dirty) {
                    tile.draw_back(Game.bg);
                    tile.dirty = false;
                    dirty = true;
                }
            }
        }
        if(dirty) sc.cache(0x500000000l,Game.bg.getDest());
    }

    private void cache_background(int w,int h)
    {
        int px = Board.screen_width + timer_width;
        int py = Board.screen_height;
        if( w * py > h * px) {
            w = (py * w + h/2) / h;
            h = py;
        } else {
            h = (px * h + w/2) / w;
            w = px;
        }

        if( Game.bg == null)
            Game.bg = new BitmapBlitter(sc,w,h);

        if( w != Game.bg.getWidth() ||
            h != Game.bg.getHeight()) dirty = true;

        if( dirty) {
            Game.bg.setSize(w, h);
            draw_backdrop(Game.bg);
            Game.bg.transform(1f,w-px,0f);
            draw_back(Game.bg);
            sc.cache(0x500000000l,Game.bg.getDest());
            dirty = false;
        }
    }

    public synchronized void paint(Blitter b)
    {
        int px = Board.screen_width + timer_width;
        int width = b.getWidth();
        int height = b.getHeight();
        scale = width * Board.screen_height < height * px ?
            (float)width / px : (float)height / Board.screen_height;
        offsetx = width - px*scale;

        // Draw the background
        cache_background(width, height);
        refresh_bg_cache();
        b.blit(0x500000000l,
            0,0,Game.bg.getWidth(),Game.bg.getHeight(),
            0,0,width,height);

        b.transform( scale, offsetx, 0.0f);

        // Draw the middle
        draw_mid(b);

        // Draw all of the marbles
        for(Marble marble : marbles)
            marble.draw(b);

        // Draw the foreground
        draw_fore(b);

        // Trigger the update step
        if(onPainted != null) onPainted.run();
    }

    public void set_tile( int x, int y, Tile tile) {
        tiles[y][x] = tile;
        tile.setxy(x,y);

        // If it's a trigger, keep track of it
        if( tile instanceof Trigger)
            trigger = (Trigger)tile;

        // If it's a stoplight, keep track of it
        if( tile instanceof Stoplight)
            stoplight = (Stoplight)tile;
    }

    public void set_launch_timer( int passes) {
        launch_timer = passes;
        launch_timeout_start = (Marble.marble_size +
            (horiz_tiles * Tile.tile_size - Marble.marble_size)
                * passes) / Marble.marble_speed;
    }

    public void set_board_timer(int seconds) {
        board_timeout_start = seconds * Game.frames_per_sec;
        board_timeout = board_timeout_start;
    }

    public void activateMarble( Marble m) {
        marbles.add(m);
        updateLiveCounter();
    }

    public void deactivateMarble( Marble m) {
        marbles.remove(m);
        updateLiveCounter();
    }

    private void updateLiveCounter() {
        liveCounterCanvas.drawColor(0,PorterDuff.Mode.CLEAR);
        int live = marbles.size();
        if( live > live_marbles_limit) live = live_marbles_limit;
        String s = live+" / "+live_marbles_limit;
        liveCounterCanvas.drawText( s, 0, Marble.marble_size*4/5, paint);
        sc.cache( 0x100000002l, liveCounter);
    }

    public void launch_marble() {
        activateMarble( new Marble(
            gr, launch_queue[0],
            board_width+Marble.marble_size/2,
            Marble.marble_size/2, 3));
        for( int i=0; i < launch_queue.length-1; ++i)
            launch_queue[i] = launch_queue[i+1];
        launch_queue[launch_queue.length-1] =
            colors.charAt(gr.random.nextInt(colors.length()))-'0';
        launch_timeout = launch_timeout_start;
        launch_queue_offset = Marble.marble_size;
    }

    public void affect_marble( Marble marble)
    {
        int cx = marble.left + Marble.marble_size/2;
        int cy = marble.top - Marble.marble_size/2;

        // Bounce marbles off of the top
        if( cy == Marble.marble_size/2) {
            marble.direction = 2;
            return;
        }

        int effective_cx = cx + Marble.marble_size/2 * Marble.dx[marble.direction];
        int effective_cy = cy + Marble.marble_size/2 * Marble.dy[marble.direction];

        if( cy < 0) {
            if(cx == Marble.marble_size/2) {
                marble.direction = 1;
                return;
            }
            if( cx == Tile.tile_size * horiz_tiles - Marble.marble_size/2
                && marble.direction == 1) {
                marble.direction = 3;
                return;
            }

            // The special case of new marbles at the top
            effective_cx = cx;
            effective_cy = cy + Marble.marble_size;
        }

        int tile_x = effective_cx / Tile.tile_size;
        int tile_y = effective_cy / Tile.tile_size;
        int tile_xr = cx - tile_x * Tile.tile_size;
        int tile_yr = cy - tile_y * Tile.tile_size;

        if( tile_x >= horiz_tiles) return;

        Tile tile = tiles[tile_y][tile_x];

        if( cy < 0 && marble.direction != 2) {
            // The special case of new marbles at the top
            if( tile_xr == Tile.tile_size / 2 && ((tile.paths & 1) == 1)) {
                if( tile instanceof Wheel) {
                    Wheel w = (Wheel)tile;
                    if( w.spinpos > 0 || w.marbles[0] != -3) return;
                    w.marbles[0] = -2;
                    marble.direction = 2;
                    this.launch_marble();
                } else if( this.marbles.size() < live_marbles_limit) {
                    marble.direction = 2;
                    this.launch_marble();
                }
            }
        } else
            tile.affect_marble( this, marble, tile_xr, tile_yr);
    }

    private Tile whichTile(int posx, int posy) {
        // Determine which tile the pointer is in
        int tile_x = posx / Tile.tile_size;
        int tile_y = (posy - Marble.marble_size) / Tile.tile_size;
        if( tile_x >= 0 && tile_x < horiz_tiles &&
            tile_y >= 0 && tile_y < vert_tiles) {
            return tiles[tile_y][tile_x];
        }
        return null;
    }

    public synchronized void downEvent(int pointerId, float x, float y)
    {
        if(board_state != INCOMPLETE) return;
        if(scale == 0f) return;
        int posx = Math.round((x - offsetx) / scale);
        int posy = Math.round(y / scale);
        Point pos = down.get(pointerId);
        if( pos == null) {
            down.put(pointerId,new Point(posx,posy));
        } else {
            pos.x = posx;
            pos.y = posy;
        }
    }

    public synchronized void upEvent(int pointerId, float x, float y)
    {
        if(board_state != INCOMPLETE) return;
        if(scale == 0f) return;
        int posx = Math.round((x - offsetx) / scale);
        int posy = Math.round(y / scale);
        final Point dpos = down.get(pointerId);
        if(dpos == null) return;
        final int downx = dpos.x, downy = dpos.y;
        int dx = posx - downx;
        int dy = posy - downy;
        int dx2 = dx*dx;
        int dy2 = dy*dy;
        if(paused) {
            if(dx2+dy2 <= Marble.marble_size*Marble.marble_size)
                setPaused(false);
            return;
        }
        Tile downtile = whichTile(downx,downy);
        if(downtile == null) return;
        int downtile_x = downx / Tile.tile_size;
        int downtile_y = (downy - Marble.marble_size) / Tile.tile_size;
        int tile_xr = downx-(downtile_x*Tile.tile_size);
        int tile_yr = downx-Marble.marble_size-(downtile_y*Tile.tile_size);

        // Use a distance threshold to decide whether the gesture is a tap
        // or a flick.  But use a smaller threshold if the flick starts near
        // a hole position and heads in the outward direction.
        int flickThreshold = Marble.marble_size;
        int dir = (dx2>dy2)?(dx>0?1:3):(dy>0?2:0);
        int xmo = tile_xr-gr.holecenters_x[0][dir];
        int ymo = tile_yr-gr.holecenters_y[0][dir];
        int nearThreshold = Marble.marble_size * 5/3;
        boolean startedNearMarble =
            (xmo*xmo+ymo*ymo) <= nearThreshold * nearThreshold;
        if(startedNearMarble) flickThreshold /= 2;
        if(dx2+dy2 <= flickThreshold*flickThreshold) {
            downtile.click(this, tile_xr, tile_yr);
        } else {
            downtile.flick(this, tile_xr, tile_yr, dir);
        }
    }

    public boolean _load(GameResources gr, int level)
        throws IOException
    {
        BufferedReader f = new BufferedReader( new InputStreamReader(
            gr.openRawResource( R.raw.all_boards)));

        // Skip the previous levels
        int j = 0;
        while( j < vert_tiles * level) {
            String line = f.readLine();
            if( line==null) {
                f.close();
                return false;
            }
            if( line.isEmpty()) continue;
            if( line.charAt(0) == '|') j += 1;
        }

        Vector<Tile> teleporters = new Vector<Tile>();
        String teleporter_names = "";
        String stoplight = default_stoplight;

        int numwheels = 0;
        int boardtimer = -1;

        j = 0;
        while( j < vert_tiles) {
            String line = f.readLine();
            if(line.isEmpty()) continue;

            if( line.charAt(0) != '|') {
                if( line.startsWith("name="))
                    name = line.substring(5);
                else if( line.startsWith("maxmarbles="))
                    live_marbles_limit = Integer.parseInt(line.substring(11));
                else if( line.startsWith("launchtimer="))
                    set_launch_timer( Integer.parseInt(line.substring(12)));
                else if( line.startsWith("boardtimer="))
                    boardtimer = Integer.parseInt(line.substring(11));
                else if( line.startsWith("colors=")) {
                    colors = "";
                    for( char c : line.substring(7).toCharArray()) {
                        if( c >= '0' && c <= '7') {
                            colors = colors + c;
                            colors = colors + c;
                            colors = colors + c;
                        } else if( c == '8') {
                            // Crazy marbles are one-third as common
                            colors = colors + c;
                        }
                    }
                } else if( line.startsWith("stoplight=")) {
                    stoplight = "";
                    for(char c : line.substring(10).toCharArray())
                        if( c >= '0' && c <= '7')
                            stoplight = stoplight + c;
                }

                continue;
            }

            for( int i=0; i < horiz_tiles; ++i) {
                char type = line.charAt(i*4+1);
                char paths = line.charAt(i*4+2);
                int pathsint = paths-'0';
                if( paths == ' ') pathsint = 0;
                else if( paths >= 'a') pathsint = paths-'a'+10;
                char color = line.charAt(i*4+3);
                int colorint = 0;
                if( color == ' ') colorint = 0;
                else if( color >= 'a') colorint = color-'a'+10;
                else if( color >= '0' && color <= '9') colorint = color-'0';
                else colorint = 0;

                Tile tile = null;
                if( type == 'O') {
                    tile = new Wheel(this, pathsint);
                    numwheels += 1;
                } else if( type == '%') tile = new Trigger(this, colors);
                else if( type == '!') tile = new Stoplight(this, stoplight);
                else if( type == '&') tile = new Painter(this, pathsint, colorint);
                else if( type == '#') tile = new Filter(this, pathsint, colorint);
                else if( type == '@') {
                    if( color == ' ') tile = new Buffer(this, pathsint, -1);
                    else tile = new Buffer(this, pathsint, colorint);
                }
                else if( type == ' ' ||
                    (type >= '0' && type <= '8')) tile = new Tile(this, pathsint);
                else if( type == 'X') tile = new Shredder(this, pathsint);
                else if( type == '*') tile = new Replicator(this, pathsint, colorint);
                else if( type == '^') {
                    if( color == ' ') tile = new Director(this, pathsint, 0);
                    else if( color == '>') tile = new Switch(this, pathsint, 0, 1);
                    else if( color == 'v') tile = new Switch(this, pathsint, 0, 2);
                    else if( color == '<') tile = new Switch(this, pathsint, 0, 3);
                } else if( type == '>') {
                    if( color == ' ') tile = new Director(this, pathsint, 1);
                    else if( color == '^') tile = new Switch(this, pathsint, 1, 0);
                    else if( color == 'v') tile = new Switch(this, pathsint, 1, 2);
                    else if( color == '<') tile = new Switch(this, pathsint, 1, 3);
                } else if( type == 'v') {
                    if( color == ' ') tile = new Director(this, pathsint, 2);
                    else if( color == '^') tile = new Switch(this, pathsint, 2, 0);
                    else if( color == '>') tile = new Switch(this, pathsint, 2, 1);
                    else if( color == '<') tile = new Switch(this, pathsint, 2, 3);
                } else if( type == '<') {
                    if( color == ' ') tile = new Director(this,pathsint, 3);
                    else if( color == '^') tile = new Switch(this, pathsint, 3, 0);
                    else if( color == '>') tile = new Switch(this, pathsint, 3, 1);
                    else if( color == 'v') tile = new Switch(this, pathsint, 3, 2);
                }
                else if( type == '=') {
                    if( teleporter_names.indexOf(color) >= 0) {
                        Tile other = teleporters.get( teleporter_names.indexOf(color));
                        tile = new Teleporter( this, pathsint, (Teleporter)other);
                    } else {
                        tile = new Teleporter( this, pathsint, null);
                        teleporters.addElement( tile);
                        teleporter_names = teleporter_names + color;
                    }
                }

                this.set_tile( i, j, tile);

                if( type >= '0' && type <= '8') {
                    int direction;
                    if( color == '^') direction = 0;
                    else if( color == '>') direction = 1;
                    else if( color == 'v') direction = 2;
                    else direction = 3;
                    activateMarble( new Marble(gr, type-'0',
                        tile.left + Tile.tile_size/2,
                        tile.top + Tile.tile_size/2,
                        direction));
                }
            }

            j += 1;
        }
        if( boardtimer < 0) boardtimer = default_board_timer * numwheels;
        this.set_board_timer( boardtimer);
        f.close();
        return true;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        if(paused == this.paused) return;
        pause_changed = SystemClock.uptimeMillis();
        this.paused = paused;
    }
}

