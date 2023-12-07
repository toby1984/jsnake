package de.codesourcery.jsnake;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.HeadlessException;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

public class Main extends JFrame
{
    private static final int GRID_X = 20;
    private static final int GRID_Y = 20;

    private static final boolean USE_GAME_CONTROLLER = false;

    private static final Color SNAKE_COLOR = Color.RED;
    private static final int APPLES_PER_LEVEL = 10;

    private static final String APPLE_PATH = "/apple.png";

    private record Apple(int x, int y) {}

    private final Set<Apple> apples = new HashSet<>();

    private boolean gameOver;

    private final Random rnd = new Random( 0xdeadbeefL );

    private int level = 1;

    private int applesLeft;
    private int applesEaten;

    private long frameCount= 0;
    private Direction direction = Direction.UP;

    private final BlockingQueue<GamepadEventReader.ButtonPress> inputQueue = new LinkedBlockingQueue<>();

    private final Snake snake = new Snake();

    private final JPanel panel = new JPanel() {

        private float dx, dy;

        private BufferedImage apple;

        private BufferedImage getApple(int width,int height)
        {
            if ( apple == null || ( apple.getWidth() != width && apple.getHeight() != height) )
            {
                apple = load( APPLE_PATH, width, height );
            }
            return apple;
        }

        {
            setDoubleBuffered( true );
            if ( USE_GAME_CONTROLLER )
            {
                try
                {
                    GamepadEventReader.registerListener( btnAction -> {
                        if ( btnAction instanceof GamepadEventReader.ButtonPress press ) {
                            //noinspection ResultOfMethodCallIgnored
                            inputQueue.offer( press );
                        }
                    });
                }
                catch( IOException e )
                {
                    throw new RuntimeException( e );
                }
            }
            else
            {
                addKeyListener( new KeyAdapter()
                {
                    @Override
                    public void keyPressed(KeyEvent e)
                    {
                        GamepadEventReader.Button tmp = switch( e.getKeyCode() )
                        {
                            case KeyEvent.VK_UP -> GamepadEventReader.Button.UP;
                            case KeyEvent.VK_DOWN -> GamepadEventReader.Button.DOWN;
                            case KeyEvent.VK_LEFT -> GamepadEventReader.Button.LEFT;
                            case KeyEvent.VK_RIGHT -> GamepadEventReader.Button.RIGHT;
                            default -> null;
                        };
                        if ( tmp != null )
                        {
                            inputQueue.offer( new GamepadEventReader.ButtonPress( tmp ) );
                        }
                    }
                } );
            }
            setFocusable( true );
            setRequestFocusEnabled( true );
            requestFocus();
        }

        @Override
        protected void paintComponent(Graphics gfx)
        {
            final Graphics2D g = (Graphics2D) gfx;
            g.clearRect( 0, 0, getWidth(), getHeight() );

            dx = getWidth() / (float) GRID_X;
            dy = getHeight() / (float) GRID_Y;

            final BufferedImage apple = getApple( (int) Math.ceil( dx ), (int) Math.ceil( dy ) );

            apples.forEach( a -> renderImage( a.x, a.y, apple, g) );

            // render snake
            final int tickCnt = ticksTillMovement();
            long cnt = frameCount / tickCnt;
            long delta = frameCount - cnt * tickCnt;
            float perc = gameOver ? 1 : delta / (float) tickCnt;

            g.setColor( Color.RED );
            for ( int i = 0, snakeSize = snake.size(); i < snakeSize; i++ )
            {
                final boolean isTail = i == 0;
                final boolean isHead = (i == snake.size() - 1);

                final Snake.BodyPart current = snake.get( i );

                if ( isHead ) {
                    renderHead( SNAKE_COLOR, current, perc, g );
                }
                else if ( isTail )
                {
                    final Direction nextDirection = snake.get( i + 1 ).direction();
                    if ( current.direction() != nextDirection )
                    {
                        renderTail( SNAKE_COLOR, current, nextDirection, perc, g );
                    }
                    else
                    {
                        renderTail( SNAKE_COLOR, current, perc, g );
                    }
                }
                else
                {
                    final int cx = round( current.x() * dx );
                    final int cy = round( current.y() * dy );
                    g.fillRect( cx, cy, round( dx ), round( dy ) );
                }
            }

            final Font fatFont2 = g.getFont().deriveFont( 16f );
            g.setFont( fatFont2 );
            g.setColor( Color.BLUE );
            g.drawString( "Apples eaten: " + applesEaten , 15, 25 );

            if ( gameOver ) {
                g.setColor( Color.RED );
                final Font fatFont = g.getFont().deriveFont( 32f );
                g.setFont( fatFont );
                final String txt = "*** GAME OVER ***";

                final Rectangle2D bounds = g.getFont().getStringBounds( txt, g.getFontMetrics().getFontRenderContext() );
                final float cx = (float) (getWidth()/2.0f - bounds.getWidth()/2.0f);
                final float cy = (float) (getHeight()/2.0f - bounds.getHeight()/2.0f);
                g.drawString( txt, round( cx ), round( cy ) );
            }
        }

        private void renderImage(int x, int y, BufferedImage image, Graphics g) {
            final int cx = round( x * dx );
            final int cy = round( y * dy );
            g.drawImage( image, cx, cy, null );
        }

        private void renderTail(Color color, Snake.BodyPart s, float fillFactor, Graphics2D g) {
            renderTail( color, s, s.direction(), fillFactor, g );
        }

        private void renderTail(Color color, Snake.BodyPart s, Direction dir, float fillFactor, Graphics2D g) {
            float perc = Math.max( 0.01f, Math.min( 1.0f, fillFactor ) );

            final float topLeftX = s.x() * dx;
            final float topLeftY = s.y() * dy;

            g.setColor( color );
            switch(dir) {
                case RIGHT -> g.fillRect( round(topLeftX+perc*dx)   , round(topLeftY)           , round(dx-dx*perc), round(dy) );
                case LEFT  -> g.fillRect( round(topLeftX), round(topLeftY)           , round(dx-dx*perc), round(dy) );
                case UP    -> g.fillRect( round(topLeftX)           , round(topLeftY), round(dx)     , round(dy - dy*perc) );
                case DOWN  -> g.fillRect( round(topLeftX)           , round(topLeftY +dy*perc)  , round(dx)     , round(dy - dy*perc) );
            }
        }

        private void renderHead(Color color, Snake.BodyPart s, float fillFactor, Graphics2D g) {
            float perc = Math.max( 0.01f, Math.min( 1.0f, fillFactor ) );

            final float topLeftX = s.x() * dx;
            final float topLeftY = s.y() * dy;

            switch(s.direction()) {
                case RIGHT -> fillHeadRect( color, s.direction(), round(topLeftX)           , round(topLeftY)           , round(dx*perc), round(dy), g );
                case LEFT  -> fillHeadRect( color, s.direction(), round(topLeftX+dx-dx*perc), round(topLeftY)           , round(dx*perc), round(dy), g);
                case UP    -> fillHeadRect( color, s.direction(), round(topLeftX)           , round(topLeftY+dy-dy*perc), round(dx)     , round(dy*perc), g );
                case DOWN  -> fillHeadRect( color, s.direction(), round(topLeftX)           , round(topLeftY)  , round(dx)     , round(dy*perc), g);
            };
        }

        private void fillHeadRect(Color color, Direction dir, int x , int y , int width, int height, Graphics g) {
            g.setColor( color );

            g.fillRect( x, y, width, height );
            g.setColor( Color.BLACK );

            if ( dir == Direction.RIGHT )
            {
                g.drawRect( round(x - dx + width), y, round(dx), round(dy) );
            }
            else if ( dir == Direction.DOWN )
            {
                g.drawRect( x, round(y + height - dy -1 ), round(dx), round(dy) );
            }
            else
            {
                g.drawRect( x, y, round(dx), round(dy) );
            }
        }
    };

    private static int round(float x) {
        return (int) Math.ceil(x);
    }

    private int ticksTillMovement() {
        return Math.max( 1, Math.round( 15 - (level - 1) * 1.5f ) );
    }

    private void placeApples() {

        int count = APPLES_PER_LEVEL;
        applesLeft = count;
        apples.clear();
        while( count > 0 ) {
            int x = rnd.nextInt( GRID_X );
            int y = rnd.nextInt( GRID_Y );
            Apple apple = new Apple( x, y );
            if ( ! snake.isBodyPartAt( x, y ) && apples.add(apple) ) {
                count--;
            }
        }
    }

    private void reset()
    {
        this.snake.clear();
        this.level = 1;
        this.gameOver = false;
        this.frameCount = 0;

        placeApples();

        // place snake
        direction = Direction.UP;
        while( true )
        {
            final int startX = rnd.nextInt( 2, GRID_X - 3 );
            final int startY = rnd.nextInt( 2, GRID_Y - 3 );

            final Snake.BodyPart s1 = new Snake.BodyPart( startX, startY, Direction.LEFT );
            final Snake.BodyPart s2 = new Snake.BodyPart( startX - 1, startY, direction );
            if ( isNoAppleAt( s1 ) && isNoAppleAt( s2 ) )
            {
                snake.add( s1 ); /// tail
                snake.add( s2 ); /// head
                break;
            }
        }

        inputQueue.clear();
    }

    private boolean isNoAppleAt(Snake.BodyPart s) {
        return isNoAppleAt( s.x(), s.y() );
    }

    private boolean isNoAppleAt(int x, int y ) {
        return apples.stream().noneMatch( apple -> apple.x == x && apple.y == y );
    }

    public Main() throws HeadlessException
    {
        super( "JSnake" );

        reset();

        getContentPane().add( panel );

        setDefaultCloseOperation( JFrame.EXIT_ON_CLOSE );

        setSize( new Dimension( 640, 480 ) );
        setLocationRelativeTo( null );
        setVisible( true );
    }

    public static void main(String[] args) throws InterruptedException, InvocationTargetException, IOException
    {
        System.setProperty("sun.java2d.opengl", "true");
        SwingUtilities.invokeAndWait( () -> {
            try
            {
                new Main().run();
            }
            catch( IOException e )
            {
                throw new RuntimeException( e );
            }
        } );
    }

    private void doPaint() {
        panel.repaint();
        Toolkit.getDefaultToolkit().sync();
    }

    private static BufferedImage load(String classPath, int width, int height) {
        final InputStream in = Main.class.getResourceAsStream( classPath );
        if ( in == null ) {
            throw new RuntimeException( "Failed to load classpath:"+classPath );
        }
        try
        {
            BufferedImage img = ImageIO.read( in );
            final Image scaled = img.getScaledInstance( width, height, BufferedImage.SCALE_SMOOTH );
            if ( !(scaled instanceof BufferedImage buf ) ) {
                BufferedImage result = new BufferedImage( width, height, img.getType() );
                final Graphics2D gfx = result.createGraphics();
                gfx.drawImage( scaled, 0, 0, null );
                gfx.dispose();
                return result;
            }
            return buf;
        }
        catch( IOException e )
        {
            throw new RuntimeException( e );
        }
    }

    private GamepadEventReader.Button drainInput() {

        GamepadEventReader.Button last = null;
        while ( true ) {
            final GamepadEventReader.ButtonPress head = inputQueue.poll();
            if ( head == null ) {
                break;
            }
            last = head.button;
        }
        return last;
    }

    private void run() throws IOException
    {
        final ActionListener gameLoop = _ -> {

            if ( gameOver )
            {
                if ( drainInput() != null )
                {
                    reset();
                }
                return;
            }

            Direction newDirection = switch( drainInput() )
            {
                case UP -> Direction.UP;
                case DOWN -> Direction.DOWN;
                case LEFT -> Direction.LEFT;
                case RIGHT -> Direction.RIGHT;
                case null -> null;
            };

            // prevent impossible direction changes
            if ( newDirection != null && newDirection != direction.reversed() ) {
                direction = newDirection;
            }

            if ( (++frameCount % ticksTillMovement() != 0) )
            {
                doPaint();
                return;
            }

            // advance snake in movement direction
            final int newX = snake.head().x() + direction.dx;
            final int newY = snake.head().y() + direction.dy;

            // check collisions
            if ( isOutsidePlayingField( newX, newY ) )
            {
                gameOver = true;
                inputQueue.clear();
                doPaint();
                return;
            }

            final boolean hitMyself = snake.isBodyPartAt( newX, newY );
            snake.add( new Snake.BodyPart( newX, newY, direction ) );
            final Optional<Apple> apple = apples.stream().filter( a -> a.x() == newX && a.y() == newY ).findFirst();
            if ( apple.isPresent() )
            {
                applesEaten++;
                applesLeft--;
                apples.remove( apple.get() );
                if ( applesLeft == 0 )
                {
                    placeApples();
                    level++;
                }
            }
            else if ( !hitMyself )
            {
                snake.removeTailBodyPart();
            }

            if ( hitMyself )
            {
                gameOver = true;
                inputQueue.clear();
            }
            doPaint();
        };

        // run game loop with 60 FPS (16 ms per frame)
        final Timer t = new Timer( 16, gameLoop );
        t.start();
    }

    private static boolean isOutsidePlayingField(int newX, int newY)
    {
        return newX < 0 || newY < 0 || newX >= GRID_X || newY >= GRID_Y;
    }
}