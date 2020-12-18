
package org.apache.nutch.parse.html;

public class TokenObjectClass {
	
	String value;
	boolean Isurdu;
	boolean IsDigit;

	public boolean get_IsDigit()
	{
		return this.IsDigit;
	}
	public void set_IsDigit(boolean b)
	{
		this.IsDigit=b;
	}
	public String get_value()
	{
		return this.value;
	}
	public boolean get_IsUrdu()
	{
		return this.Isurdu;
	}
	public void set_value(String value)
	{
		this.value=value;
	}
	public void set_IsUrdu(boolean b)
	{
		this.Isurdu=b;
	}
}
