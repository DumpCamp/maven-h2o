/* 
 * Copyright(c) 2005 Center for E-Commerce Infrastructure Development, The
 * University of Hong Kong (HKU). All Rights Reserved.
 *
 * This software is licensed under the GNU GENERAL PUBLIC LICENSE Version 2.0 [1]
 * 
 * [1] http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt
 */

package hk.hku.cecid.piazza.commons.dao;

/**
 * The <code>CacheException</code> indicate an error 
 * for DVO cacher.
 * 
 * Creation Date: 12/2/2007
 * 
 * @author Twinsen Tsang 
 * @version 1.0.0
 * @since	1.0.0
 */
public class CacheException extends Exception {
	
	/**
	 * Backward compatible UID.
	 */
	private static final long serialVersionUID = 8165097003004319747L;

	/**
     * Creates a new instance of CacheException.
     */
    public CacheException() {
        super();
    }

    /**
     * Creates a new instance of CacheException.
     * 
     * @param message the error message.
     */
    public CacheException(String message) {
        super(message);
    }

    /**
     * Creates a new instance of CacheException.
     * 
     * @param cause the cause of this exception.
     */
    public CacheException(Throwable cause) {
        super(cause);
    }

    /**
     * Creates a new instance of CacheException.
     * 
     * @param message the error message.
     * @param cause the cause of this exception.
     */
    public CacheException(String message, Throwable cause) {
        super(message, cause);
    }
}
