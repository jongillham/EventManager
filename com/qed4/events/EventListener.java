package com.qed4.events;

/**
 * The interface that receives events when they are fired from <code>EventManager.fireEvent</code>.
 * 
 */
public interface EventListener<T extends Event> {

    /**
     * Called when an event that is being listened for has been fired from <code>EventManager.fireEvent</code>
     * 
     * @param e Can be cast to the event being listened for.
     */
    public void eventFired(T e);
}
