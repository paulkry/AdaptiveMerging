package mergingBodies3D;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;

import javax.vecmath.Matrix3d;
import javax.vecmath.Point3d;
import javax.vecmath.Vector3d;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;

import mergingBodies3D.Merging.MergeParameters;

/**
 * Adapted from 2D verions... 
 * 
 * TODO: Also... consider pooling rigid collections as they probably get create and destroyed often??  
 * Perhaps not as bad as contacts... but just the same!!  The internal lists (contacts and bodies) will
 * benefit from not being re-allocated and regrown too.
 * 
 * 
 */
public class RigidCollection extends RigidBody {

	/** List of RigidBody of the collection */
	protected ArrayList<RigidBody> bodies = new ArrayList<RigidBody>();
	
	/**
	 * List of Contact in the collection: Contact between RigidBody of the collection
	 */
	protected HashSet<Contact> internalContacts = new HashSet<Contact>();
	protected ArrayList<Contact> tmpContacts = new ArrayList<Contact>();
	
	MotionMetricProcessor motionMetricProcessor = new MotionMetricProcessor();
	
	static MergeParameters mergeParams;
	
	// Temp variable
	/** new center of mass*/
	private Point3d com = new Point3d(); 
	private double totalMassInv = 0; 

	Color color = new Color();
	
	/**
	 * Creates a RigidCollection from two RigidBody.
	 * 
	 * @param body1
	 * @param body2
	 */
	public RigidCollection(RigidBody body1, RigidBody body2) {

		// These bodies being added to the collection, with the collection being new,
		// their state w.r.t the collection frame is unchanged as C2W and W2C are
		// Identity

		generateColor();
	
		if (body1 instanceof PlaneRigidBody) {
			copyFrom(body2);
			body2.parent = this;
			bodies.add(body2);
			addBody(body1);
			
		} else {
			copyFrom(body1);
			body1.parent = this;
			bodies.add(body1);
			addBody(body2);
		}
	}
	
	public void generateColor() {
		color.setRandomColor();
		if (col!=null) {
			col[0] = color.x; col[1] = color.y; col[2] = color.z; col[3] = 1;
		} else {
			col = new float[] { color.x, color.y, color.z, 1 };
		}
	}
	
	/**
	 * Zero working variables for accumulating forces during a time step
	 */
	public void clearBodies() {
		for (RigidBody body : bodies) {
			applyVelocitiesTo(body);
			body.clear();
		}
	}

	/**
	 * Copy velocities of given body
	 * 
	 * @param body
	 */
	private void copyFrom(RigidBody body) {
		v.set(body.v);
		omega.set( body.omega );
		
		x.set(body.x);
		theta.set( body.theta );
		
		massLinear = body.massLinear;
		minv = body.minv;
		
		massAngular.set( body.massAngular );
		massAngular0.set( body.massAngular0 );
		jinv.set( body.jinv );
		jinv0.set( body.jinv0 );
		
		pinned = body.pinned;
		sleeping = body.sleeping;
		canSpin = body.canSpin;
		spinner = body.spinner;
		
		boundingBoxB.clear();
		for (Point3d point: body.boundingBoxB) 
			boundingBoxB.add(new Point3d(point));
	}

	/**
	 * Adds a body to the collection
	 * This generally follows a 3 step process
	 * <p><ul>
	 * <li> 1) Adds the body to the list, and updates the velocity to be a
	 *    weighted average of the collection and the new body.  This velocity
	 *    is updated to be in the collection's COM frame, even though other 
	 *    methods to update the COM are not yet called.  Because the velocity
	 *    is stored in a world aligned frame, this has little impact on the rest
	 *    of the steps.
	 * <li> 2) Mass COM and Inertia are updated.  The inertia will be with
	 *    respect to the current rotation of the collection, so some care necessary
	 *    to initalize massAnular0 and jinv0 too.
	 * <li> 3) Rotation is optimized for the bounding volumes of the different bodies in
	 *     the collection. (currently not implemented)  Because this changes the 
	 *     rest rotation of the collection, it will influence the rest inertia.
	 * <li> 4) Transformations are updated (jinv too, perhaps wasteful), and likewise
	 *     the body to collection transforms 
	 * </p></ul>
	 * @param body body to add
	 */
	public void addBody(RigidBody body) {
		body.parent = this;
		bodies.add(body);
		updateCollectionState(body);
		addBodyInternalMethod(body);
		
		updateInertiaRestAndInvert();
		updateRotationalInertiaFromTransformation(); // TODO: is this really necessary?
		updateBodiesTransformations();
	}

	/**
	 * Adds given list of bodies the collection
	 * @param bodies list of bodies
	 */
	public void addBodies(Collection<RigidBody> bodies) {
		for (RigidBody body : bodies) {
			body.parent = this;
			this.bodies.add(body);
			updateCollectionState(body);
			addBodyInternalMethod(body);
		}
		
		updateInertiaRestAndInvert();
		updateRotationalInertiaFromTransformation();
		updateBodiesTransformations();
	}

	/**
	 * Adds a collection to the collection
	 * @param collection collection to add
	 */
	public void addCollection(RigidCollection collection) {
		for (RigidBody body : collection.bodies) {
			body.parent = this;
			bodies.add(body);
		}

		updateCollectionState(collection);
	    addBodyInternalMethod(collection);
	    
	    updateInertiaRestAndInvert();
		updateRotationalInertiaFromTransformation();
		updateBodiesTransformations();
	}
	
	/**
	 * Update collection pinned, sleeping and canSpin condition
	 * @param body
	 */
	private void updateCollectionState(RigidBody body) {
 		pinned = (pinned || body.pinned);

		sleeping = (sleeping || body.sleeping);
		body.sleeping = false;
		
		if (body.canSpin) {
			canSpin = true;
			spinner = body.spinner;
		}
	}
	
	/**
	 * Adds a body to the collection (internal method, for factoring purposes).
	 * 
	 * @param body body to add
	 */
	private void addBodyInternalMethod( RigidBody body ) {

		updateTheta(body); // computed in world coordinates, is that correct? theta should be the rotations from inertial frame right?
		
		if ( pinned ) { 
			v.set(0,0,0);
			omega.set(0,0,0);

			massAngular.setZero(); // actually infinity.. but won't be used??
			massAngular0.setZero();
			jinv.setZero();
			jinv0.setZero();
			
        	massLinear = 0;
			minv = 0;
		} else {
			com.set(x);
			com.scale(massLinear);
			com.scaleAdd(body.massLinear, body.x, com);
			totalMassInv = 1./(body.massLinear + massLinear);
			com.scale( totalMassInv );
			
			updateVelocitiesFrom(body, com, totalMassInv);
			updateInertia(body, com);
			updateBB(body); // set temporarily in world coordinates
			
			massLinear += body.massLinear; // used in updateInertia, this update should stay here
			minv = totalMassInv;
			x.set(com); // finally update com of the new collection
			// back in collection coordinates
			for (Point3d point : boundingBoxB)
				this.transformB2W.inverseTransform(point);
		}
	}

	/**
	 * Update's the collection's velocity given a newly added body
	 * The velocities should match... but we'll do a mass weighted
	 * average in the new COM frame to make sure that things work out.
	 * Note: this might not do what you expect if either body is pinned!!
	 * 
	 * CAREFUL this will set the velocity at the new COM position!!
	 * 
	 * @param body
	 */
	private void updateVelocitiesFrom(final RigidBody body, final Point3d com, final double totalMassInv) {

		Vector3d r = new Vector3d();
		Vector3d wxr = new Vector3d();
		Vector3d tmp1 = new Vector3d();
		Vector3d tmp2 = new Vector3d();

		r.sub( com, body.x );
		wxr.cross( body.omega, r );
		tmp1.add( wxr, body.v );
		tmp1.scale( body.massLinear );
		
		r.sub( com, x );
		wxr.cross( omega, r );
		tmp2.add( wxr, v );
		tmp2.scale( massLinear );
		
		tmp1.add( tmp2 );
		tmp1.scale( totalMassInv );
		
		v.set(tmp1); 

		omega.scale( massLinear );
		omega.scaleAdd( body.massLinear, body.omega, omega );
		omega.scale( totalMassInv );
	}
	
	/**
	 * Compute theta of the collection from covariance's eigen vectors
	 */
	private void updateTheta(RigidBody newBody) {

		/*if (newBody instanceof PlaneRigidBody) // TODO: what if we copied a PlaneRigidBody...
			return;

		int N = 16;
		Point3d meanPos = new Point3d();
		Point3d p = new Point3d();
		
		for (int i=0; i<2; i++) {
			RigidBody body = (i==0)? this: newBody;
			for (Point3d point : body.boundingBoxB) {
				p.set(point);
				body.transformB2W.transform(p);
				meanPos.add(p);
			}
		}
		meanPos.scale(1.f/N);

		Vector3d v = new Vector3d();
		MyMatrix3f Mtmp = new MyMatrix3f();
		MyMatrix3f covariance = new MyMatrix3f();
		
		for (int i=0; i<2; i++) {
			RigidBody body = (i==0)? this: newBody;
			for (Point3d point : body.boundingBoxB) {
				p.set(point);
				body.transformB2W.transform(p);
				v.sub(p, meanPos);
				Mtmp.m00 = (float)(v.x*v.x); Mtmp.m01 = (float)(v.x*v.y); Mtmp.m02 = (float)(v.x*v.z);
				Mtmp.m10 = (float)(v.y*v.x); Mtmp.m11 = (float)(v.y*v.y); Mtmp.m12 = (float)(v.y*v.z);
				Mtmp.m20 = (float)(v.z*v.x); Mtmp.m21 = (float)(v.z*v.y); Mtmp.m22 = (float)(v.z*v.z);
				covariance.add(Mtmp);
			}
		}
		covariance.mul(1.f/N);
		covariance.getEigen(Mtmp);
		Mtmp.normalize();
		
		theta.set(Mtmp);*/
		
		theta.setIdentity();
	}
	
	/**
	 * For each body in collection, determines the transformations to go from body
	 * to collection But also, make each body's x and theta in collection, relative
	 * to this x and theta
	 */
	private void updateBodiesTransformations() {
		for (RigidBody body : bodies)
			body.transformB2C.multAinvB( transformB2W, body.transformB2W );		
	}
	
	
	// temp variables
	private Point3d bbmaxB = new Point3d();
	private Point3d bbminB = new Point3d();
	private Point3d p = new Point3d();
	/**
	 * Update collection bounding box from bodies list
	 */
	private void updateBB() {
		if ( boundingBoxB == null || boundingBoxB.isEmpty()) return;
		bbmaxB.set(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE);
		bbminB.set( Double.MAX_VALUE,  Double.MAX_VALUE,  Double.MAX_VALUE);
		
		for (RigidBody body : bodies) {	
			
			if (body instanceof PlaneRigidBody)
				continue;
			
			for (Point3d point : body.boundingBoxB) {
				p.set(point);
				body.transformB2C.transform(p);
				bbmaxB.x = Math.max(bbmaxB.x, p.x);
				bbmaxB.y = Math.max(bbmaxB.y, p.y);
				bbmaxB.z = Math.max(bbmaxB.z, p.z);
				bbminB.x = Math.min(bbminB.x, p.x);
				bbminB.y = Math.min(bbminB.y, p.y);
				bbminB.z = Math.min(bbminB.z, p.z);
			}
		}
					
		boundingBoxB.get(0).set(bbmaxB);
		boundingBoxB.get(1).set(bbmaxB.x, bbminB.y, bbminB.z);
		boundingBoxB.get(2).set(bbminB.x, bbmaxB.y, bbminB.z);
		boundingBoxB.get(3).set(bbminB.x, bbminB.y, bbmaxB.z);
		boundingBoxB.get(4).set(bbminB);
		boundingBoxB.get(5).set(bbminB.x, bbmaxB.y, bbmaxB.z);
		boundingBoxB.get(6).set(bbmaxB.x, bbminB.y, bbmaxB.z);
		boundingBoxB.get(7).set(bbmaxB.x, bbmaxB.y, bbminB.z);
	}
	
	/**
	 * Update collection bounding box with new body
	 * @param newBody
	 */
	private void updateBB(RigidBody body) {
		if ( boundingBoxB == null || boundingBoxB.isEmpty() ) return;
		bbmaxB.set(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE);
		bbminB.set( Double.MAX_VALUE,  Double.MAX_VALUE,  Double.MAX_VALUE);
			
		if (body instanceof PlaneRigidBody)
			return;
		
		for (int i=0; i<2; i++) {
			RigidBody b = (i==0)? this: body;
			for (Point3d point : b.boundingBoxB) {
				p.set(point);
				b.transformB2W.transform(p);
				bbmaxB.x = Math.max(bbmaxB.x, p.x);
				bbmaxB.y = Math.max(bbmaxB.y, p.y);
				bbmaxB.z = Math.max(bbmaxB.z, p.z);
				bbminB.x = Math.min(bbminB.x, p.x);
				bbminB.y = Math.min(bbminB.y, p.y);
				bbminB.z = Math.min(bbminB.z, p.z);
			}
		}
		
		// Temporarily in world coordinates
		boundingBoxB.get(0).set(bbmaxB);
		boundingBoxB.get(1).set(bbmaxB.x, bbminB.y, bbminB.z);
		boundingBoxB.get(2).set(bbminB.x, bbmaxB.y, bbminB.z);
		boundingBoxB.get(3).set(bbminB.x, bbminB.y, bbmaxB.z);
		boundingBoxB.get(4).set(bbminB);
		boundingBoxB.get(5).set(bbminB.x, bbmaxB.y, bbmaxB.z);
		boundingBoxB.get(6).set(bbmaxB.x, bbminB.y, bbmaxB.z);
		boundingBoxB.get(7).set(bbmaxB.x, bbmaxB.y, bbminB.z);
	}
	

	/**
	 * Update massAngular0, jinv, and jinv0
	 */
    protected void updateInertiaRestAndInvert() {
    	if ( !pinned ) {
			jinv.invert( massAngular );	 // is this avoidable?  :/	
			transformB2W.computeRTMR( massAngular, massAngular0 );
			transformB2W.computeRTMR( jinv, jinv0 );	
		}
    }
	
	/**
	 * Removes given list of bodies from the collection
	 * @param bodiesToRemove
	 */
	public void removeBodies(Collection<RigidBody> bodiesToRemove) {
		bodies.removeAll(bodiesToRemove);
		
		boolean wasPinned = pinned;
		pinned = false;
		for (RigidBody body : bodies)
			pinned = (pinned || body.pinned);
		
		theta.setIdentity(); // TODO: fix me...?
		
		if (pinned) { // should not be really necessary...
			v.set(0,0,0);
			omega.set(0,0,0);

			massAngular.setZero(); // actually infinity.. but won't be used??
			massAngular0.setZero();
			jinv.setZero();
			jinv0.setZero();
			
        	massLinear = 0;
			minv = 0;
		} else if (wasPinned) { // unfortunately if the collection was pinned, we have to recompute everything
			x.set(0.,0.,0.);
			massLinear = 0;
			for (RigidBody body : bodies) {
				x.scaleAdd(body.massLinear, body.x, x); 
				massLinear += body.massLinear;
			}
			minv = 1./massLinear;
			x.scale(minv); 
			
			computeInertia();
			updateInertiaRestAndInvert();
		} else { 
			for (RigidBody body : bodiesToRemove) {
				com.set(x); // save com with the body in it
				
				com.scale(massLinear); // use com as tmp variable to avoid the call this.scaleAdd(...,this)
				x.scaleAdd(-body.massLinear, body.x, com); 
				com.scale(1./massLinear); // put back com as it was 
				
				massLinear -= body.massLinear; // mass with the body removed
				minv = 1./massLinear;
				x.scale(minv); // x with the body removed
				
				updateInertiaReverse(body, com);
			}
			updateInertiaRestAndInvert();
		}

		updateRotationalInertiaFromTransformation();
		updateBodiesTransformations();
		updateBB(); // this can not be updated incrementally
	}
	

	/** worker variables, held to avoid memory thrashing */
	private Matrix3d op = new Matrix3d();
	
	/**
	 * Compute inertia from bodies
	 * @param bodyToRemove
	 * @param com
	 */
	private void computeInertia() {
		
		massAngular.setZero();
		for (RigidBody body: bodies) {
			massAngular.add( body.massAngular );
			getOp(body, x, op);
			massAngular.add( op );	
		}
	}
	
	/**
	 * update inertia from newBody
	 * @param newBody
	 * @param com
	 */
	private void updateInertia(final RigidBody newBody, final Point3d com) {
		
		massAngular0.setZero(); // used as temp variable, will actually be set after the update of massAngular
		for (int i=0; i<2; i++) {
			RigidBody body = (i==0)? this: newBody;
					
			massAngular0.add( body.massAngular );
			getOp(body, com, op);
			massAngular0.add( op );	
		}
		massAngular.set(massAngular0);
	}
	
	/**
	 * Update inertia when we remove a body
	 * @param bodyToRemove
	 * @param com
	 */
	private void updateInertiaReverse(final RigidBody bodyToRemove, final Point3d com) {

		massAngular.sub( bodyToRemove.massAngular ); // only the angular mass of the body to remove should be removed
		for (int i=0; i<2; i++) {
			RigidBody body = (i==0)? this: bodyToRemove;	
			
			getOp(body, com, op);
			massAngular.sub( op );	
		}
	}
	
	//TODO: change my name
	void getOp(RigidBody body, Point3d com, Matrix3d op) {
		// translate inertia tensor to center of mass
		// should certainly have a b.x squared type term for the mass being at a distance...
		//			I -[p]    J  0     I  0      (i.e., Ad^T M Ad, see MLS textbook or Goswami's paper)
		//			0  I      0 mI    [p] I
		//
		//			I -[p]   J   0
		//			0   I   m[p] 0
		//
		//			Thus.. J - mI [p][p] in the upper left...
		// recall lemma 2.3: [a] = a a^T - ||a||^2 I
		double x = body.x.x - com.x; 
		double y = body.x.y - com.y;
		double z = body.x.z - com.z;
		double x2 = x*x;
		double y2 = y*y;
		double z2 = z*z;
		op.m00 = y2+z2;  op.m01 = -x*y;  op.m02 = -x*z;
		op.m10 = -y*x;   op.m11 = x2+z2; op.m12 = -y*z;
		op.m20 = -z*x;   op.m21 = -z*y;  op.m22 = x2+y2;
		op.mul( body.massLinear );
	}

	/** 
	 * Migrates the contacts of the BPC to the internal contacts. 
	 * @param bpc
	 */
	public void addToInternalContact(BodyPairContact bpc) {
		tmpContacts.clear();
		for (Contact contact : bpc.contactList) {
			// need to make new to not mess with the memory pools 
			Contact c = new Contact(contact);
			c.newThisTimeStep = false;
			tmpContacts.add( c );
			internalContacts.add( c );
		}
		bpc.contactList.clear();
		bpc.contactList.addAll( tmpContacts );
	}

	/**
	 * Add bpc and external bpc to the collection BodyPairContact
	 * 
	 * @param bpc
	 */
	public void addBPCsToCollection(BodyPairContact bpc) {
		bpc.addToBodyListsParent();

		// add the external bpc to the collection bodyPairContactList
		for (int i=0; i<2; i++) {
			RigidBody body = bpc.getBody(i);
			for (BodyPairContact bpcExt : body.bodyPairContacts)
				bpcExt.addToBodyListsParent();
		}
	}
	
	@Override
	public void advanceTime( double dt ) {

		super.advanceTime( dt );

		if ( pinned || sleeping )
			return;

		updateBodiesPositionAndTransformations();

		// Advance velocities for internal bodies
		if (!mergeParams.enableUnmergeRelativeMotionCondition.getValue())
			applyVelocitiesToBodies();
	}
	
	@Override
	public void advanceVelocities( double dt ) {
		super.advanceVelocities(dt);
		applyVelocitiesToBodies();
	}
	
	@Override
	public void advancePositions( double dt ) {
		super.advancePositions(dt);
		updateBodiesPositionAndTransformations();
	}
	
	@Override
	public void advancePositionsPostStabilization( double dt ) {
		super.advancePositionsPostStabilization(dt);
		updateBodiesPositionAndTransformations();
	}

	/**
	 * Updates bodies position, orientation, and transformations
	 */
	public void updateBodiesPositionAndTransformations() {
		for (RigidBody body : bodies) {
			
			// update transformations
			body.transformB2W.mult( transformB2W, body.transformB2C );			
			
			if ( ! pinned ) {  // a normal update would do this... so we should do it too for a correct single cycle update.
				body.transformB2W.computeRM0RT( body.jinv0, body.jinv );
				body.transformB2W.computeRM0RT( body.massAngular0, body.massAngular );
	        } 
		}
	}

	/**
	 * Updates bodies velocities
	 */
	protected void applyVelocitiesToBodies() {
		for (RigidBody body : bodies) {
			applyVelocitiesTo(body);
		}
	}

	/**
	 * Apply the linear and angular velocities to the given body
	 * 
	 * @param body
	 */
	public void applyVelocitiesTo(RigidBody body) {
		if ( pinned ) { 
			if (v.lengthSquared() > 1e-14 || omega.lengthSquared() > 1e-14)
				System.err.println("[applyVelocitiesTo] velocities of pinned body is not zero. " + omega.toString() );
		}

		Vector3d r = new Vector3d();
		Vector3d wxr = new Vector3d();
		r.sub( body.x, x );
		wxr.cross( omega, r );
		body.v.add( v, wxr ); // sets the value of the sum
		body.omega.set( omega );
	}

	/**
	 * Makes body ready to be used by system... converts everything to world
	 * coordinates and makes body independent of collection ... does not do anything
	 * to the collection itself.
	 */
	public void unmergeBody(RigidBody body) {
		if (!body.isInCollection(this)) {
			System.err.println("[unmergeBody] Not suppose to happen.");
			return;
		} else {
			applyVelocitiesTo(body);
			body.deltaV.setZero();
			body.parent = null;
		}
	}

	/**
	 * Go through all bodies and makes sure all the BodyContacts of each body is in
	 * the collection
	 */
	public void fillInternalBodyContacts() {
		bodyPairContacts.clear();
		internalContacts.clear();
		for (RigidBody body : bodies) {
			for (BodyPairContact bpc : body.bodyPairContacts) {
				if (!bodyPairContacts.contains(bpc)) {
					bodyPairContacts.add(bpc);
					RigidBody otherBody = bpc.getOtherBody(body);
					if (body.isInSameCollection(otherBody)) {
						bpc.inCollection = true;
						for (Contact contact : bpc.contactList) {
							if (!internalContacts.contains(contact)) {
								internalContacts.add(contact);
							}
						}
					}
				}
			}
		}
	}

	/**
	 * We need to also add the other contacts that body has with the same collection
	 * it's being merged with. Must also add the BodyPairContact around the body
	 * that didn't reach 50 time steps but are still part of the same parents. The
	 * input parameter is the body being merged, and the body pair contact removal
	 * queue so that any BPCs identified in this call can also be later removed.
	 */
	public void addIncompleteContacts(RigidBody body, LinkedList<BodyPairContact> removalQueue) {
		for (BodyPairContact bpc : body.bodyPairContacts) {
			if (bpc.body1.isInSameCollection(bpc.body2) && !bpc.inCollection) {
				bpc.inCollection = true;
				bpc.motionMetricHist.clear();
				bpc.contactStateHist.clear();
				body.parent.addToInternalContact(bpc);
				body.parent.addBPCsToCollection(bpc);
				removalQueue.add(bpc);
			}
		}
	}

	/**
	 * input parameter is a collection being merged . we must add also all the
	 * incomplete contacts this parent has with other collections.
	 */
	public void addIncompleteCollectionContacts(RigidCollection collection, LinkedList<BodyPairContact> removalQueue) {
		for (RigidBody body : collection.bodies) {
			addIncompleteContacts(body, removalQueue);
		}
	}

	public void displayInternalContactForces(GLAutoDrawable drawable, double dt ) {
		for (Contact c : internalContacts ) {
			c.displayContactForce( drawable, true, dt ); // blue inside collection
		}
	}

	public void displayInternalContactLocations( GLAutoDrawable drawable ) {
		for (Contact c : internalContacts ) {
			c.display( drawable, true ); // blue inside collection
		}
	}

	/**
	 * displays the Body Collection as lines between the center of masses of each
	 * rigid body to the other. Uses a string arrayList to check if a connection has
	 * already been drawn.
	 * 
	 * @param drawable
	 */
    private static final float[] colGraph = new float[] { 0, 0.2f, 0, 0.25f };
	public void displayContactGraph(GLAutoDrawable drawable) {
		GL2 gl = drawable.getGL().getGL2();

		gl.glLineWidth(5);
		gl.glMaterialfv( GL.GL_FRONT_AND_BACK, GL2.GL_AMBIENT_AND_DIFFUSE, colGraph, 0 );
		gl.glBegin(GL.GL_LINES);
		for (BodyPairContact bpc : bodyPairContacts) {
			if (bpc.inCollection) {
				gl.glVertex3d(bpc.body1.x.x, bpc.body1.x.y, bpc.body1.x.z);
				gl.glVertex3d(bpc.body2.x.x, bpc.body2.x.y, bpc.body2.x.z);
			}
		}
		gl.glEnd();
	}

	/**
	 * displays cycles (from merge condition)
	 * 
	 * @param drawable
	 */
	public void displayCycles(GLAutoDrawable drawable, int size) {

		for (BodyPairContact bpc : bodyPairContacts) {
			if (bpc.inCycle) {
				if (bpc.contactList.isEmpty())
					System.err.println("[displayCycles] The list of contact is empty. This should not happen. Probably due to an unwanted merge (concave?).");
				else {
					for (Contact contact : bpc.contactList) 
						contact.display(drawable, true);
				}
			}
		}
	}

	@Override
	public void displayBB(GLAutoDrawable drawable) {
		super.displayBB(drawable);
		/*for (RigidBody body : bodies) {
			body.displayBB(drawable);
		}*/
	}
}
