/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.jivesoftware.smackx.jingle;

import org.jivesoftware.smackx.AbstractExtensionElement;

/**
 * Represents the <tt>parameter</tt> elements described in XEP-0167.
 *
 * @author Emil Ivov
 * @author Eng Chong Meng
 */
public class ParameterExtension extends AbstractExtensionElement
{
    /**
     * The name of the "parameter" element.
     */
    public static final String ELEMENT = "parameter";

    /**
     * The name of the <tt>name</tt> parameter in the <tt>parameter</tt> element.
     */
    public static final String NAME_ATTR_NAME = "name";

    /**
     * The name of the <tt>value</tt> parameter in the <tt>parameter</tt> element.
     */
    public static final String VALUE_ATTR_NAME = "value";

    /**
     * Creates a new {@link ParameterExtension} instance.
     */
    public ParameterExtension()
    {
        super(ELEMENT, null);
    }

    /**
     * Creates a new {@link ParameterExtension} instance and sets the given name and value.
     */
    public ParameterExtension(String name, String value)
    {
        super(ELEMENT, null);

        setName(name);
        setValue(value);
    }

    /**
     * Sets the name of the format parameter we are representing here.
     *
     * @param name the name of the format parameter we are representing here.
     */
    public void setName(String name)
    {
        super.setAttribute(NAME_ATTR_NAME, name);
    }

    /**
     * Returns the name of the format parameter we are representing here.
     *
     * @return the name of the format parameter we are representing here.
     */
    public String getName()
    {
        return super.getAttributeAsString(NAME_ATTR_NAME);
    }

    /**
     * Sets that value of the format parameter we are representing here.
     *
     * @param value the value of the format parameter we are representing here.
     */
    public void setValue(String value)
    {
        super.setAttribute(VALUE_ATTR_NAME, value);
    }

    /**
     * Returns the value of the format parameter we are representing here.
     *
     * @return the value of the format parameter we are representing here.
     */
    public String getValue()
    {
        return super.getAttributeAsString(VALUE_ATTR_NAME);
    }
}
