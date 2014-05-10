package stork.feather.util;

import stork.feather.*;

/**
 * A base class for anonymous {@code Resource}s. {@code AnonymousSession}s and
 * {@code AnonymousResource}s are generally used for creating virtual {@code
 * Sink}s and {@code Tap}s that emit {@code Slice}s that aren't based on real
 * addressable {@code Resource}s, yet must declare an associated {@code
 * Resource} to work within the framework. This class represents a virtual
 * {@code Resource} such implementations may use.
 */
public class AnonymousResource
extends Resource<AnonymousSession,AnonymousResource> {
  /**
   * Create an {@code AnonymousResource} whose parent is the canonical {@code
   * AnonymousSession}.
   */
  public AnonymousResource() { super(Session.ANONYMOUS); }

  // Used in AnonymousSession to create a root.
  AnonymousResource(AnonymousSession session) {
    super(session);
  }

  private AnonymousResource(String name, AnonymousResource parent) {
    super(name, parent);
  }

  public AnonymousResource select(String name) {
    return new AnonymousResource(name, this);
  }
}
