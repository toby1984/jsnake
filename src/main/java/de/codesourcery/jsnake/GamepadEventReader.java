package de.codesourcery.jsnake;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class GamepadEventReader
{
    private static final InputEvent POISON_PILL =
        new InputEvent( 0, 0, EventType.EV_UNKNOWN, new UnrecognizedCode( 0 ), 0 );

    private final ByteBuffer buffer = ByteBuffer.allocateDirect( 8 + 8 + 2 + 2 + 4 );
    private final FileChannel in;
    private final BlockingQueue<InputEvent> queue;
    private final AtomicInteger dropCounter = new AtomicInteger();
    private volatile Predicate<InputEvent> filter = _ -> true;
    private volatile boolean terminate;
    private volatile boolean crashed;

    private Thread thread;

    public sealed interface Code permits AbsCodes, UnrecognizedCode { }

    public record UnrecognizedCode(int id) implements Code {}

    public enum AbsCodes implements Code {
        ABS_X			(0x00),
        ABS_Y			(0x01),
        ABS_Z			(0x02),
        ABS_RX			(0x03),
        ABS_RY			(0x04),
        ABS_RZ			(0x05),
        ABS_THROTTLE	(0x06),
        ABS_RUDDER		(0x07),
        ABS_WHEEL		(0x08),
        ABS_GAS			(0x09),
        ABS_BRAKE		(0x0a),
        ABS_HAT 		(0x10),
        ABS_HAT0Y		(0x11),
        ABS_HAT1X		(0x12),
        ABS_HAT1Y		(0x13),
        ABS_HAT2X		(0x14),
        ABS_HAT2Y		(0x15),
        ABS_HAT3X		(0x16),
        ABS_HAT3Y		(0x17),
        ABS_PRESSURE		(0x18),
        ABS_DISTANCE		(0x19),
        ABS_TILT_X		(0x1a),
        ABS_TILT_Y		(0x1b),
        ABS_TOOL_WIDTH		(0x1c),
        ABS_VOLUME		(0x20),
        ABS_PROFILE		(0x21),
        ABS_MISC		(0x28);

        public final int id;

        private static final class Holder
        {
            private static final Map<Integer, AbsCodes> map = new HashMap<>();
        }

        AbsCodes(int id) {
            this.id = id;
            Holder.map.put( id, this );
        }

        public static AbsCodes parseId(int id) {
            final AbsCodes event = Holder.map.get( id & 0xffff );
            return event;
        }
    }

    public enum EventType
    {
        EV_SYN(0x00),
        EV_KEY(0x01),
        EV_REL(0x02),
        EV_ABS(0x03),
        EV_MSC(0x04),
        EV_SW(0x05),
        EV_LED(0x11),
        EV_SND(0x12),
        EV_REP(0x14),
        EV_FF(0x15),
        EV_PWR(0x16),
        EV_FF_STATUS(0x17),
        EV_UNKNOWN(0xff);

        public final int id;

        private static final class Holder
        {
            private static final Map<Integer, EventType> map = new HashMap<>();
        }

        EventType(int id) {
            this.id = id;
            Holder.map.put( id, this );
        }

        public static EventType parseId(int id) {
            final EventType event = Holder.map.get( id & 0xffff );
            return event == null ? EV_UNKNOWN : event;
        }
    }

    public record InputEvent(long tvSecs, long tvUsec, EventType type, Code code, long value) {

        public InputEvent(long tvSecs, long tvUsec, EventType type, Code code, long value)
        {
            this.tvSecs = tvSecs;
            this.tvUsec = tvUsec;
            this.type = type ;
            this.code = code;
            this.value = value & 0xffffffffL;
        }

        public boolean hasCode(Code t1) {
            return t1.equals( this.code );
        }

        public boolean hasCode(Code t1, Code t2) {
            return hasCode( t1 ) || hasCode( t2 );
        }

        public boolean hasType(EventType t1) {
            return t1.equals( this.type );
        }

        public boolean hasType(EventType t1, EventType t2) {
            return hasType( t1 ) || hasType( t2 );
        }

        public static InputEvent parse(ByteBuffer buffer) {

            /*
             * struct timeval {
             *    __time_t tv_sec; // 64 bit
             *    __suseconds_t tv_usec; // 64 bit
             * }
             *
             * struct input_event {
             *     struct timeval time; // 2 x UINT64
             *     __u16 type;
             *     __u16 code;
             *     __s32 value;
             * };
             *
             *  https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/include/uapi/linux/input-event-codes.h
             */
            final long tvSec = buffer.getLong();
            final long tvUsec = buffer.getLong();

            final short type = buffer.getShort();
            final short codeId = buffer.getShort();
            final int value = buffer.getInt();

            Code code = AbsCodes.parseId( codeId );
            if ( code == null ) {
                code = new UnrecognizedCode( codeId );
            }

            return new InputEvent( tvSec, tvUsec , EventType.parseId( type), code, value);
        }
    }

    public GamepadEventReader(String devicePath, int maxQueueSize) throws IOException {
        buffer.order( ByteOrder.LITTLE_ENDIAN );
        in = FileChannel.open( Paths.get( devicePath ) );
        queue = new ArrayBlockingQueue<>(maxQueueSize);
    }

    public synchronized void start() {
        if ( thread == null || ! thread.isAlive() ) {
            crashed = false;
            terminate = false;
            dropCounter.set( 0 );
            Thread t = new Thread( this::run, "controller-event-reader" );
            t.setDaemon( true );
            t.start();
            thread = t;
        }
    }

    /**
     * Try to get event from queue (non-blocking).
     *
     * @return
     */
    public Optional<InputEvent> poll() {
        if ( crashed ) {
            throw new RuntimeException( "Crashed" );
        }
        return Optional.ofNullable( unwrap( queue.poll() ) );
    }

    /**
     * Try to get event from queue (blocking).
     * @    return
     * @throws InterruptedException
     */
    public InputEvent take() throws InterruptedException
    {
        if ( crashed ) {
            throw new InterruptedException( "Crashed" );
        }
        return unwrap(queue.take());
    }

    private InputEvent unwrap(InputEvent ev) {
        if ( crashed || ev == POISON_PILL ) {
            throw new RuntimeException( "Crashed" );
        }
        return ev;
    }

    /**
     * Try to get event from queue (blocking for a given amount of time).
     *
     * @param value
     * @param unit
     * @return
     * @throws InterruptedException
     */
    public Optional<InputEvent> poll(long value, TimeUnit unit) throws InterruptedException
    {
        final InputEvent ev = queue.poll( value, unit );
        if ( crashed ) {
            throw new InterruptedException( "Crashed" );
        }
        return Optional.ofNullable( unwrap(ev) );
    }

    public int getEventDropCounter()
    {
        return dropCounter.get();
    }

    public GamepadEventReader setFilter(Predicate<InputEvent> filter)
    {
        if ( filter == null ) {
            throw new NullPointerException();
        }
        this.filter = filter;
        return this;
    }

    public void run() {

        boolean success = false;
        try
        {
            mainLoop();
            success = true;
        }
        catch( IOException e )
        {
            e.printStackTrace();
        }
        finally {
            if ( ! success ) {
                crashed = true;
                try
                {
                    queue.put( POISON_PILL );
                }
                catch( InterruptedException e )
                {
                    throw new RuntimeException( e );
                }
            }
        }
    }

    private void mainLoop() throws IOException
    {
        try ( in )
        {
            while(true) {
                try
                {
                    buffer.clear();
                    final int read = in.read( buffer );
                    if ( read != buffer.capacity() )
                    {
                        final String msg = "read() error: " + read + " but expected " + buffer.capacity();
                        throw new IOException( msg );
                    }
                    buffer.flip();
                    final InputEvent record = InputEvent.parse( buffer );
                    if ( filter.test( record ) ) {
                        if ( ! queue.offer( record ) ) {
                            dropCounter.incrementAndGet();
                            System.err.println("Input event dropped.");
                        }
                    }
                }
                catch( IOException e )
                {
                    if ( terminate )
                    {
                        break;
                    }
                    throw new RuntimeException( e );
                }
            }
        }
    }

    public enum Button
    {UP, DOWN, LEFT, RIGHT,}

    public static sealed abstract class ButtonAction permits ButtonPress, ButtonRelease
    {
        public final Button button;

        protected ButtonAction(Button button)
        {
            this.button = button;
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + "[" + button + "]";
        }
    }

    public static final class ButtonPress extends ButtonAction
    {
        public ButtonPress(Button button)
        {
            super( button );
        }
    }

    public static final class ButtonRelease extends ButtonAction
    {
        public ButtonRelease(Button button)
        {
            super( button );
        }
    }

    public static void registerListener(Consumer<ButtonAction> listener) throws IOException
    {
        final String devicePath = "/dev/input/by-id/usb-HJC_Game_GAME_FOR_WINDOWS___00000000-event-joystick";
        final GamepadEventReader reader = new GamepadEventReader( devicePath, 1024 );
        reader.setFilter( x -> x.hasType( EventType.EV_ABS ) && x.hasCode(AbsCodes.ABS_HAT, AbsCodes.ABS_HAT0Y) );
        reader.start();

        final Thread t = new Thread( new Runnable()
        {
            private Button lastPressed;

            @Override
            public void run()
            {
                while (true)
                {
                    final InputEvent event;
                    try
                    {
                        event = reader.take();
                        // System.out.println( "GOT: " + event );

                        if ( event.type() == EventType.EV_ABS )
                        {
                            switch( event.code )
                            {
                                case AbsCodes.ABS_HAT ->
                                {
                                        /*
              LEFT: ABS_HAT, value = 0xffffffff (pressed, release is value==0)
              RIGHT: ABS_HAT, value = 0x1 (pressed, release is value==0)
                                         */
                                    if ( event.value() == 0xffffffffL )
                                    {
                                        listener.accept( new ButtonPress( lastPressed = Button.LEFT ) );
                                    }
                                    else if ( event.value() == 1L )
                                    {
                                        listener.accept( new ButtonPress( lastPressed = Button.RIGHT ) );
                                    }
                                    else if ( event.value() == 0L )
                                    {
                                        if ( lastPressed != null )
                                        {
                                            try
                                            {
                                                listener.accept( new ButtonRelease( lastPressed ) );
                                            }
                                            finally
                                            {
                                                lastPressed = null;
                                            }
                                        }
                                    }
                                    else
                                    {
                                        System.err.println( "*** ignored event (1): " + event );
                                    }
                                }
                                case AbsCodes.ABS_HAT0Y ->
                                {
/*
              UP: ABS_HAT0Y, value = 0xffffffff (pressed, release is value==0)
              DOWN: ABS_HAT0Y, value = 1 (pressed, release is value==0)
 */
                                    if ( event.value() == 0xffffffffL )
                                    {
                                        listener.accept( new ButtonPress( lastPressed = Button.UP ) );
                                    }
                                    else if ( event.value() == 1L )
                                    {
                                        listener.accept( new ButtonPress( lastPressed = Button.DOWN ) );
                                    }
                                    else if ( event.value() == 0L )
                                    {
                                        if ( lastPressed != null )
                                        {
                                            try
                                            {
                                                listener.accept( new ButtonRelease( lastPressed ) );
                                            }
                                            finally
                                            {
                                                lastPressed = null;
                                            }
                                        }
                                    }
                                    else
                                    {
                                        System.err.println( "*** ignored event (2): " + event );
                                    }
                                }
                                default -> System.err.println( "*** ignored event (3): " + event );
                            }
                        }
                        else
                        {
                            System.err.println( "*** ignored event (4): " + event );
                        }
                    }
                    catch( InterruptedException e )
                    {
                        throw new RuntimeException( e );
                    }
                }
            }
        }, "" );
        t.setDaemon( true );
        t.start();
    }

    public static void main(String[] args) throws IOException, InterruptedException
    {
        final String devicePath = "/dev/input/by-id/usb-0b0e_Jabra_Link_380_08C8C2361557-event-if03";
        final GamepadEventReader reader = new GamepadEventReader( devicePath, 1024 );
        reader.setFilter( x -> true );
        reader.start();

        while(true)
        {
            final InputEvent event = reader.take();
            System.out.println("GOT: "+event);
        }

//        GamepadEventReader.registerListener( action -> {
//            System.out.println("GOT "+action);
//        } );
//        Thread.sleep( 1000000 );
    }
}