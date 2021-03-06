/*
 * #%L
 * Fork of MDB Tools (Java port).
 * %%
 * Copyright (C) 2008 - 2013 Open Microscopy Environment:
 *   - Board of Regents of the University of Wisconsin-Madison
 *   - Glencoe Software, Inc.
 *   - University of Dundee
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as 
 * published by the Free Software Foundation, either version 2.1 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */

package mdbtools.examples;

import java.sql.DriverManager;
import java.sql.Connection;
import java.sql.ResultSet;

public class mdb_tables
{
  public static void main(String[] args)
  {
    try
    {
      Class.forName("mdbtools.jdbc.Driver");
      String filename;
      filename = args[0];

      Connection conn = DriverManager.getConnection("jdbc:mdbtools:" + filename);
      ResultSet rset = conn.getMetaData().getTables(null,null,null,null);
      while (rset.next())
        System.out.println(rset.getString("table_name"));
      rset.close();
      conn.close();
    }
    catch(Exception e)
    {
      e.printStackTrace();
    }
  }
}
