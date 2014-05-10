package stork.feather;

import stork.feather.util.*;

/**
 * A {@code Sink} is a destination for {@link Slice}s emitted by {@link Tap}s.
 * It is the {@code Sink}'s responsibility to "drain" {@code Slice}s to the
 * associated physical resource (or other data consumer). {@code Slice}s should
 * be drained as soon as possible to, and be retained only if necessary.
 * <p/>
 * If a {@code Slice} cannot be drained immediately, the {@code Sink} should
 * call {@code pause()} to prevent the attached {@code Tap} from emitting
 * further {@code Slice}s. Once a {@code Slice} is drained to the {@code Sink},
 * the {@code Slice} cannot be requested again, and it is the {@code Sink}'s
 * responsibility to guarantee that the {@code Slice} is eventually drained.
 *
 * @see Tap
 * @see Slice
 *
 * @param <R> The destination {@code Resource} type.
 */
public abstract class Sink<R extends Resource> extends PipeElement<R> {
  private ProxyTransfer<?,R> transfer;

  /** The destination {@code Resource}. */
  public final R root;

  /**
   * Create a {@code Sink} with an anonymous root {@code Resource}.
   */
  public Sink() { this(Resources.ANONYMOUS); }

  /**
   * Create a {@code Sink} with the given {@code Resource} as the root.
   *
   * @param root the {@code Resource} this {@code Sink} receives data for.
   */
  public Sink(R root) { super(root); }

  // Get the transfer, or throw an IllegalStateException if the transfer is not
  // ready.
  private final ProxyTransfer<?,R> transfer() {
    if (transfer == null)
      throw new IllegalStateException();
    return transfer;
  }

  /**
   * Attach this {@code Sink} to a {@code Tap}. Once this method is called,
   * {@link #start()} will be called and the sink may begin draining data from
   * the tap. This is equivalent to calling {@code tap.attach(this)}.
   *
   * @param tap a {@link Tap} to attach.
   * @throws NullPointerException if {@code tap} is {@code null}.
   * @throws IllegalStateException if a {@code Tap} has already been attached.
   */
  public final <S> ProxyTransfer<S,R> attach(Tap<S> tap) {
    if (tap == null)
      throw new NullPointerException();
    if (transfer != null)
      throw new IllegalStateException("A Tap is already attached.");
    return tap.attach(this);
  }

  /**
   * This can be overridden by {@code Sink} implementations to initialize the
   * transfer of {@code Slice}s for a {@code Resource}.
   */
  public Bell<?> initialize(Relative<Resource> resource) {
    return null;
  }

  /**
   * Drain a {@code Slice} to the endpoint storage system. This method returns
   * as soon as possible, with the actual I/O operation taking place
   * asynchronously.
   * <p/>
   * If the {@code Slice} cannot be drained immeditately due to congestion,
   * {@code pause()} should be called, and {@code resume()} should be called
   * when the channel is free to transmit data again.
   *
   * @param slice a {@code Slice} being drained through the pipeline.
   * @throws IllegalStateException if this method is called when the pipeline
   * has not been initialized.
   */
  public abstract void drain(Relative<Slice> slice);

  /**
   * This can be overridden by {@code Sink} implementations to finalize the
   * transfer of {@code Slice}s for a {@code Resource}.
   */
  public void finalize(Relative<Resource> resource) { }

  /**
   * {@code Sink} implementations can override this to handle initialization
   * {@code Exception}s from upstream. By default, this method will throw the
   * {@code Exception} back to the transfer mediator.
   *
   * @param path the path of the {@code Resource} which had an exception.
   * @throws Exception if {@code error} was not handled.
   */
  //protected Bell<?> initialize(Relative<Exception> error) throws Exception {
  //  throw error.getCause();
  //}

  public boolean random() { return false; }

  public int concurrency() { return 1; }

  /**
   * Called when an upstream tap encounters an error while downloading a {@link
   * Resource}. Depending on the nature of the error, the sink should decide to
   * either abort the transfer, omit the file, or take some other action.
   *
   * @param error the error that occurred during transfer, along with
   * contextual information
   */
  //void handle(ResourceException error);

  public final void pause() {
    transfer().pause();
  }

  public final void resume() {
    transfer().resume();
  }

  /**
   * Get the root {@code Resource} of the attached {@code Tap}.
   *
   * @return The root {@code Resource} of the attached {@code Tap}.
   * @throws IllegalStateException if a tap has not been attached.
   */
  public final Resource source() {
    return transfer().source();
  }

  /**
   * Get the {@code Resource} specified by {@code path} relative to the root of
   * the attached {@code Tap}.
   *
   * @return The {@code Resource} specified by {@code path} relative to the
   * root of the attached {@code Tap}.
   * @throws IllegalStateException if a tap has not been attached.
   */
  public final Resource source(Path path) {
    return transfer.source(path);
  }
}
