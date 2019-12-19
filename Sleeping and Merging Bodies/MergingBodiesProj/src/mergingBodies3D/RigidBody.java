package mergingBodies3D;

import java.util.ArrayList;
import java.util.HashMap;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;

import javax.vecmath.Matrix3d;
import javax.vecmath.Point2d;
import javax.vecmath.Point3d;
import javax.vecmath.Vector2d;
import javax.vecmath.Vector3d;

/**
 * Simple 2D rigid body based on image samples
 * @author kry
 */
public class RigidBody {

    /** Unique identifier for this body */
    public int index;
    
    /** Variable to keep track of identifiers that can be given to rigid bodies */
    static public int nextIndex = 0;
    
    /** Block approximation of geometry */
    ArrayList<Block> blocks;
    
    /** Boundary blocks */
    ArrayList<Block> boundaryBlocks;
        
    BVNode root;
    
    /** accumulator for forces acting on this body */
    Vector3d force = new Vector3d();
    
    /** accumulator for torques acting on this body */
    Vector3d torque = new Vector3d();
    
    Matrix3d massAngular;
    
    double massLinear;
        
    public boolean pinned;
    
    public boolean selected;
    
    /** TODO: should this even live here?  perhaps this should only be in the mouse spring */
    public Point3d selectedPoint = new Point3d();
    
    /**
     * Transforms points in Body coordinates to World coordinates
     */
    RigidTransform transformB2W = new RigidTransform();
    
    /**
     * Transforms points in World coordinates to Body coordinates
     */
    RigidTransform transformW2B = new RigidTransform();
    
    /** linear velocity */
    public Vector3d v = new Vector3d();
    
    /** Position of center of mass in the world frame */
    public Point3d x = new Point3d();
    
    /** initial position of center of mass in the world frame */
    Point3d x0 = new Point3d();
    
    /** orientation of the body TODO: refactor to be R later?? */
    public Matrix3d theta;
    
    /** angular velocity in radians per second */
    public Vector3d omega;

    /** inverse of the linear mass, or zero if pinned */
    double minv;
    
    /** inverse of the angular mass, or zero if pinned */
    Matrix3d jinv;
    
    /**
     * Creates a new rigid body from a collection of blocks
     * @param blocks
     * @param boundaryBlocks
     */
    public RigidBody( ArrayList<Block> blocks, ArrayList<Block> boundaryBlocks ) {

        this.blocks = blocks;
        this.boundaryBlocks = boundaryBlocks;        
        // compute the mass and center of mass position        
        for ( Block b : blocks ) {
            double mass = b.getColourMass();
            massLinear += mass;            
            x0.x += b.j * mass;
            x0.y += b.i * mass; 
            x0.z = 0; // TODO: x0.z += b.k * mass; // if we were to have voxels...
        }
        x0.scale ( 1 / massLinear );
        // set block positions in world and body coordinates 
        for ( Block b : blocks ) {
            b.pB.x = b.j - x0.x;
            b.pB.y = b.i - x0.y;
            //b.pB.z = b.k - x0.z;
        }
        // compute the rotational inertia
        final Point3d zero = new Point3d(0,0,0);
        for ( Block b : blocks ) {
            double mass = b.getColourMass();
            // TODO: 
            //massAngular += mass*b.pB.distanceSquared(zero);
        }
        massAngular.setIdentity(); // TODO: THIS IS WRONG!!! Compute the angular mass!
        
        // prevent zero angular inertia in the case of a single block
//        if ( blocks.size() == 1 ) {
//            Block b = blocks.get(0);
//            double mass = b.getColourMass();
//            massAngular = mass * (1+1)/12;
//        }
       
        x.set(x0);        
        transformB2W.set( theta, x );
        transformW2B.set( theta, x );
        transformW2B.invert();
                
        root = new BVNode( boundaryBlocks, this );
        
        pinned = isAllBlueBlocks();
        
        if ( pinned ) {
            minv = 0;
            jinv.setZero();
        } else {
            minv = 1/massLinear;
            jinv.invert(massAngular);
        }
        
        // set our index
        index = nextIndex++;
    }
    
    /**
     * Creates a copy of the provided rigid body 
     * @param body
     */
    public RigidBody( RigidBody body ) {
        blocks = body.blocks;
        boundaryBlocks = body.boundaryBlocks;
        massLinear = body.massLinear;
        massAngular = body.massAngular;
        x0.set( body.x0 );
        x.set( body.x );
        theta = body.theta;
        omega = body.omega;
        // we can share the blocks and boundary blocks...
        // no need to update them as they are in the correct body coordinates already        
        updateTransformations();
        // We do need our own bounding volumes!  can't share!
        root = new BVNode( boundaryBlocks, this );        
        pinned = body.pinned;
        minv = body.minv;
        jinv = body.jinv;
        // set our index
        index = nextIndex++;
    }
    
    /**
     * Updates the B2W and W2B transformations
     */
    public void updateTransformations() {
        transformB2W.set( theta, x );
        transformW2B.set( theta, x );
        transformW2B.invert();
    }
    
    
    /**
     * Apply a contact force specified in world coordinates
     * @param contactPointW
     * @param contactForceW
     */
    public void applyContactForceW( Point3d contactPointW, Vector3d contactForceW ) {
        force.add( contactForceW );
        // TODO: Compute the torque applied to the body 
        
        
    }
    
    /**
     * Advances the body state using symplectic Euler, first integrating accumulated force and torque 
     * (which are then set to zero), and then updating position and angle.  The internal rigid transforms
     * are also updated. 
     * @param dt step size
     */
    public void advanceTime( double dt ) {
        if ( !pinned ) {            
            // TODO: use torques to advance the angular state of the rigid body
            
            v.x += 1.0 / massLinear * force.x * dt;
            v.y += 1.0 / massLinear * force.y * dt;
            v.z += 1.0 / massLinear * force.z * dt;
            x.x += v.x * dt;
            x.y += v.y * dt;
            x.z += v.z * dt;
            updateTransformations();
        }        
        force.set(0,0,0);
        torque.set(0,0,0);
    }
    
    /**
     * Computes the total kinetic energy of the body.
     * @return the total kinetic energy
     */
    public double getKineticEnergy() {
    	Vector3d result = new Vector3d();
    	massAngular.transform(omega,result);
        return 0.5 * massLinear * v.lengthSquared() + 0.5 * result.dot( omega ); 
    }
    
    /** 
     * Computes the velocity of the provided point provided in world coordinates due
     * to motion of this body.   
     * @param contactPointW
     * @param result the velocity
     */
    public void getSpatialVelocity( Point3d contactPointW, Vector3d result ) {
        result.sub( contactPointW, x );
        result.scale( omega );        
        double xpart = -result.y;
        double ypart =  result.x;
        result.set( xpart, ypart );
        result.add( v );
    }
    
    /**
     * Checks if all blocks are shades of blue
     * @return true if all blue
     */
    boolean isAllBlueBlocks() {
        for ( Block b : blocks ) {
            if ( ! (b.c.x == b.c.y && b.c.x < b.c.z) ) return false;
        }
        return true;
    }

    /**
     * Checks to see if the point intersects the body in its current position
     * @param pW
     * @return true if intersection
     */
    public boolean intersect( Point2d pW ) {
        if ( root.boundingDisc.isInDisc( pW ) ) {
            Point2d pB = new Point2d();
            transformW2B.transform( pW, pB );
            for ( Block b : blocks ) {
                if ( b.pB.distanceSquared( pB ) < Block.radius * Block.radius ) return true;
            }
        }
        return false;
    }
    
    /**
     * Resets this rigid body to its initial position and zero velocity, recomputes transforms
     */
    public void reset() {
        x.set(x0);        
        theta = 0;
        v.set(0,0);
        omega = 0;
        transformB2W.set( theta, x );
        transformW2B.set( transformB2W );
        transformW2B.invert();
    }
    
    /** Map to keep track of display list IDs for drawing our rigid bodies efficiently */
    static private HashMap<ArrayList<Block>,Integer> mapBlocksToDisplayList = new HashMap<ArrayList<Block>,Integer>();
    
    /** display list ID for this rigid body */
    int myListID = -1;
    
    /**
     * Deletes all display lists.
     * This is called when clearing all rigid bodies from the simulation, or when display lists need to be updated due to 
     * changing transparency of the blocks.
     * @param gl
     */
    static public void clearDisplayLists( GL2 gl ) {
        for ( int id : mapBlocksToDisplayList.values() ) {
            gl.glDeleteLists(id, 1);
        }
        mapBlocksToDisplayList.clear();
    }
    
    /** 
     * Draws the blocks of a rigid body
     * @param drawable
     */
    public void display( GLAutoDrawable drawable ) {
        GL2 gl = drawable.getGL().getGL2();
        gl.glPushMatrix();
        gl.glTranslated( x.x, x.y, 0 );
        gl.glRotated(theta*180/Math.PI, 0,0,1);
        if ( myListID == -1 ) {
            Integer ID = mapBlocksToDisplayList.get(blocks);
            if ( ID == null ) {
                myListID = gl.glGenLists(1);
                gl.glNewList( myListID, GL2.GL_COMPILE_AND_EXECUTE );
                for ( Block b : blocks ) {
                    b.display( drawable );
                }
                gl.glEndList();
                mapBlocksToDisplayList.put( blocks, myListID );
            } else {
                myListID = ID;
                gl.glCallList(myListID);
            }
        } else {
            gl.glCallList(myListID);
        }
        gl.glPopMatrix();
    }
    
    static public double kineticEnergyThreshold = 1e-6;
    
    /**
     * Draws the center of mass position with a circle.  The circle will be 
     * drawn differently if the block is at rest (i.e., close to zero kinetic energy)
     * @param drawable
     */
    public void displayCOM( GLAutoDrawable drawable ) {
        GL2 gl = drawable.getGL().getGL2();
        if ( getKineticEnergy() > kineticEnergyThreshold ) {
            gl.glPointSize(8);
            gl.glColor3f(0,0,0.7f);
            gl.glBegin( GL.GL_POINTS );
            gl.glVertex2d(x.x, x.y);
            gl.glEnd();
            gl.glPointSize(4);
            gl.glColor3f(1,1,1);
            gl.glBegin( GL.GL_POINTS );
            gl.glVertex2d(x.x, x.y);
            gl.glEnd();
        } else {
            gl.glPointSize(8);
            gl.glColor3f(0,0,0.7f);
            gl.glBegin( GL.GL_POINTS );
            gl.glVertex2d(x.x, x.y);
            gl.glEnd();
            gl.glPointSize(4);
            gl.glColor3f(0,0,1);
            gl.glBegin( GL.GL_POINTS );
            gl.glVertex2d(x.x, x.y);
            gl.glEnd();
        }
    }
    
}