package mergingBodies3D;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import javax.vecmath.AxisAngle4d;
import javax.vecmath.Matrix3d;
import javax.vecmath.Point3d;
import javax.vecmath.Tuple3d;
import javax.vecmath.Vector3d;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class XMLParser {
	
	private RigidBodySystem system;
	private Document document = null;
	private Element eElement;
		
	/**
	 * Method to call to parse the XML file
	 * @param system
	 * @param filename
	 */
	public void parse(RigidBodySystem system, String filename) {
		
       	String ext = filename.substring(filename.length()-3);
       	filename = filename.replaceFirst(ext,"xml");
	
		this.system = system;
		
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder = null;
		try {
			builder = factory.newDocumentBuilder();
		} catch (ParserConfigurationException e) {
			e.printStackTrace();
		}
		 
		try {
			document = builder.parse(new File(filename));
		} catch (SAXException | IOException e) {
			e.printStackTrace();
			return;
		}
		 
		document.getDocumentElement().normalize();
		 
		System.out.println("Load XML file");
		System.out.println("-------------");
		parseCollision(); 
		parseBody();
		System.out.println("-------------");
	}
	
	/**
	 * Parse collision node to set parameters common to ALL bodies?
	 * TODO: should probably have these adjust interface slider values for restitution and friction
	 * And those parameters should perhaps be available as overrides or to modulate the restition and
	 * friction parameters of bodies. 
	 */
	private void parseCollision() {
		NodeList nList = document.getElementsByTagName("collision");
		for (int temp = 0; temp < nList.getLength(); temp++) {
			Node node = nList.item(temp);
			if (node.getNodeType() == Node.ELEMENT_NODE) {
				eElement = (Element) node;
				String[] attributes = {"feedbackStiffness", "restitution", "friction"};
				for (String attribute : attributes) {
					Node n = eElement.getElementsByTagName(attribute).item(0);
					if ( n != null) {
						System.out.println(" "+attribute+" : "  + n.getTextContent());
						String[] values = n.getTextContent().split("\\s+");
						switch(attribute) {
							case "feedbackStiffness":
								system.collision.feedbackStiffness.setValue(Double.parseDouble(values[0]));
								break;
							case "restitution":
								system.collision.restitution.setValue(Double.parseDouble(values[0]));
								for (RigidBody body: system.bodies)
									body.restitution = system.collision.restitution.getValue();
								break;
							case "friction":
								system.collision.friction.setValue(Double.parseDouble(values[0]));
								for (RigidBody body: system.bodies)
									body.friction = system.collision.friction.getValue();
								break;
							default:
						}
					}
				}
				System.out.println("");
			}
		}
	}
	
	/**
	 * Parse body node
	 */
	private void parseBody() {
		NodeList nList = document.getElementsByTagName("body");
		for (int temp = 0; temp < nList.getLength(); temp++) {
			Node node = nList.item(temp);
			if (node.getNodeType() == Node.ELEMENT_NODE) {
				eElement = (Element) node;
				String type = eElement.getAttribute("type");
				String name = eElement.getAttribute("name");
				if ( type.equalsIgnoreCase("box") ) {
					createBox( name, eElement );
				} else if ( type.equalsIgnoreCase("plane") ) {
					createPlane( name, eElement );
				} else if ( type.equalsIgnoreCase("sphere") ) {
					createSphere( name, eElement );
				}
			}
		}
	}
	
	/**
	 * TODO: should include ability to change density
	 * @param name
	 * @param eElement
	 */
	private void createBox( String name, Element eElement ) {
		Vector3d s = new Vector3d( t3d( eElement.getAttribute("dim") ) );
		double density = 1;
		double massLinear = s.x*s.y*s.z * density;
		Matrix3d angularMass = new Matrix3d();
		angularMass.m00 = 1.0/12*massLinear*(s.y*s.y+s.z*s.z);
		angularMass.m11 = 1.0/12*massLinear*(s.x*s.x+s.z*s.z);
		angularMass.m22 = 1.0/12*massLinear*(s.x*s.x+s.y*s.y);				
		ArrayList<Point3d> bbB = new ArrayList<Point3d>();
		bbB.add( new Point3d( -s.x, -s.y, -s.z ) );
		bbB.add( new Point3d( -s.x, -s.y,  s.z ) );
		bbB.add( new Point3d( -s.x,  s.y, -s.z ) );
		bbB.add( new Point3d( -s.x,  s.y,  s.z ) );
		bbB.add( new Point3d(  s.x, -s.y, -s.z ) );
		bbB.add( new Point3d(  s.x, -s.y,  s.z ) );
		bbB.add( new Point3d(  s.x,  s.y, -s.z ) );
		bbB.add( new Point3d(  s.x,  s.y,  s.z ) );		
		RigidBody body = new RigidBody(massLinear, angularMass, false, bbB );
		setCommonAttributes( body, eElement );
		body.updateTransformations();
        body.geom = new RigidBodyGeomBox( s );	
        // Seems silly to deal with boxes with spheres, but this is the quick solution for now... 
        // Assume unit size tiling, and choose centers along faces
        
        double radius = 0.5;
        int nx = (int) Math.ceil( s.x/2/radius );
        int ny = (int) Math.ceil( s.y/2/radius );
        int nz = (int) Math.ceil( s.z/2/radius );
        
        ArrayList<BVSphere> L = new ArrayList<BVSphere>();
        Point3d p = new Point3d();
        // stupid, but simple: loop over the volume, and only 
        // generate spheres at surface points
        for ( int i = 0; i < nx; i++ ) {
        	boolean iboundary = i == 0 || i == nx-1;
        	for ( int j = 0; j < ny; j++ ) {
            	boolean jboundary = j == 0 || j == ny-1;
        		for ( int k = 0; k < nz; k++ ) {
                	boolean kboundary = k == 0 || k == nz-1;
                	if ( !iboundary && !jboundary && !kboundary ) continue;
            		p.x = -s.x/2 + radius + i*2*radius;
            		p.y = -s.y/2 + radius + j*2*radius;
            		p.z = -s.z/2 + radius + k*2*radius;
            		L.add( new BVSphere( p, radius * Math.sqrt(2) , body ) ); // should probably be sqrt 3... but balance what is in and what is out/
        		}
        	}
        }
        
        body.root = new BVNode( L, body );
		system.bodies.add( body );
		body.name = name;
	}

	private void createPlane( String name, Element eElement ) {
		Point3d p = new Point3d();
		Vector3d n = new Vector3d();
		p.set( t3d( eElement.getAttribute("p") ) );
		n.set( t3d( eElement.getAttribute("n") ) );
		RigidBody b = new PlaneRigidBody(p, n); // ALWAYS pinned, 
		setCommonAttributes( b, eElement ); // can still adjust friction and restitution
		system.bodies.add( b );
		b.name = name;
	}
	
	/**
	 * TODO: should include ability to change density
	 * @param name
	 * @param eElement
	 */
	private void createSphere( String name, Element eElement ) {
		double r = Double.parseDouble( eElement.getAttribute("r") );
		double density = 1;
		double massLinear = 4.0/3*Math.PI*r*r*r * density;
		Matrix3d angularMass = new Matrix3d();
		angularMass.setIdentity();
		angularMass.mul( 2.0/5*massLinear*r*r );
		ArrayList<Point3d> bbB = new ArrayList<Point3d>();
		bbB.add( new Point3d( -r, -r, -r ) );
		bbB.add( new Point3d( -r, -r,  r ) );
		bbB.add( new Point3d( -r,  r, -r ) );
		bbB.add( new Point3d( -r,  r,  r ) );
		bbB.add( new Point3d(  r, -r, -r ) );
		bbB.add( new Point3d(  r, -r,  r ) );
		bbB.add( new Point3d(  r,  r, -r ) );
		bbB.add( new Point3d(  r,  r,  r ) );		
		// seems stupid to have a set of bounding box points for a sphere, but 
		// it is needed for how we currently do relative velocity bounds.
		RigidBody body = new RigidBody(massLinear, angularMass, false, bbB );
		setCommonAttributes( body, eElement );
        body.geom = new RigidBodyGeomSphere( r );		
		BVSphere bvSphere = new BVSphere( new Point3d(), r, body );
        body.root = new BVNode( bvSphere );
		system.bodies.add( body );
		body.name = name;
	}

	/**
	 * Sets the attributes common to all bodies here
	 * @param body
	 * @param eElement
	 */
	private void setCommonAttributes( RigidBody body, Element eElement ) {
		NodeList nodeList = eElement.getChildNodes();
        for ( int i = 0; i < nodeList.getLength(); i++ ) {
        	Node n = nodeList.item(i);
            // skip all text, just process the ELEMENT_NODEs
            if ( n.getNodeType() != Node.ELEMENT_NODE ) continue;
            String tag = n.getNodeName();
            String[] values = n.getTextContent().trim().split("\\s+");        
			System.out.println(" "+tag+" : "  + n.getTextContent());
			if ( tag.equalsIgnoreCase("x") ) {
				body.x.set( t3d( values ) );
				body.x0.set( body.x );
				body.updateTransformations();
			} else if ( tag.equalsIgnoreCase("R") ) {
				AxisAngle4d aa = new AxisAngle4d();
				aa.set( asDoubles(values) );
				body.theta.set( aa );
				body.theta0.set( aa );
				body.updateTransformations();
			} else if ( tag.equalsIgnoreCase("v") ) {
				body.v.set( t3d( values ) );
			} else if ( tag.equalsIgnoreCase("omega") ) {
				body.omega.set( t3d( values ) );
			} else if ( tag.equalsIgnoreCase("restitution") ) {
				body.restitution = Double.parseDouble(values[0]);
			} else if ( tag.equalsIgnoreCase("friction") ) {
				body.friction = Double.parseDouble(values[0]);
			} else if ( tag.equalsIgnoreCase("temporarilyPinned") ) {			
				//body.temporarilyPinned = Boolean.parseBoolean(values[0]);
			} else if ( tag.equalsIgnoreCase("pinned") ) {	
				body.pinned = Boolean.parseBoolean(values[0]);
			} else if ( tag.equalsIgnoreCase("spring") ) {
				Element e = (Element) n;
				Point3d pB = new Point3d( t3d( e.getAttribute("pB") ) );
				Spring s = new Spring( pB, body );
				body.springs.add( s );
				
			}
			// could complain about unknown attributes here
		}		
		System.out.println("");
	}
	
	private Tuple3d t3d( String[] values ) {
		Vector3d v = new Vector3d();
		v.x = Double.parseDouble(values[0]);
		v.y = Double.parseDouble(values[1]);
		v.z = Double.parseDouble(values[2]);
		return v;
	}

	private Tuple3d t3d( String s ) {
		Vector3d v = new Vector3d();
        String[] values = s.trim().split("\\s+");        
		v.x = Double.parseDouble(values[0]);
		v.y = Double.parseDouble(values[1]);
		v.z = Double.parseDouble(values[2]);
		return v;
	}
	
	private double[] asDoubles( String[] s ) {
		double[] d = new double[s.length];
		for ( int i = 0; i < s.length; i++ ) {
			d[i] = Double.parseDouble(s[i]);
		}
		return d;
	}

}