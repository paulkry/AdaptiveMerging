package src;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.border.TitledBorder;
import javax.vecmath.*;
import no.uib.cipr.matrix.*;


import mintools.parameters.BooleanParameter;
import mintools.parameters.DoubleParameter;
import mintools.parameters.IntParameter;
import mintools.swing.CollapsiblePanel;
import mintools.swing.VerticalFlowPanel;
import no.uib.cipr.matrix.Matrices;

/**
 * Class for detecting and resolving collisions.  Currently this class uses penalty forces between rigid bodies.
 * @author kry
 */
public class CollisionProcessor {

    public List<RigidBody> bodies;
    
    private HashMap<String, Contact> last_timestep_map = new HashMap<String, Contact>();
    
    public ArrayList<RigidCollection> collections = new ArrayList<RigidCollection>();
    /**
     * The current contacts that resulted in the last call to process collisions
     */
    public ArrayList<Contact> contacts = new ArrayList<Contact>();
    
    /**
     * Creates this collision processor with the provided set of bodies
     * @param bodies
     */
    public CollisionProcessor( List<RigidBody> bodies ) {
        this.bodies = bodies;
    }
    
    /** keeps track of the time used for collision detection on the last call */
    double collisionDetectTime = 0;
    
    /** keeps track of the time used to solve the LCP based velocity update on the last call */
    double collisionSolveTime = 0;
    
    /**list that keeps track of all the body contacts that occured in this timestep     */
    public ArrayList<BodyContact> bodyContacts = new ArrayList<BodyContact>();
    
    /**
     * Processes all collisions 
     * @param dt time step
     */
    public void processCollisions( double dt ) {
    	
        Contact.nextContactIndex = 0;
        //remember passive contacts
        if (CollisionProcessor.use_contact_graph.getValue() || RigidBodySystem.enableMerging.getValue()) {

            rememberBodyContacts();
        }
        
     
		//the rest of the collisionProcessor
        contacts.clear();
        long now = System.nanoTime();
        broadPhase();
        collisionDetectTime = ( System.nanoTime() - now ) * 1e-9;
        if (contacts.size() == 0)  last_timestep_map.clear();
        
        
        if ( contacts.size() > 0  && doLCP.getValue() ) {
            now = System.nanoTime();
      
           PGS( dt,  now);
        }
       
            	 
    }
   
	private void rememberBodyContacts() {
    	ArrayList<BodyContact> savedBodyContacts = new ArrayList<BodyContact>();
   		for (BodyContact bc : bodyContacts) {
   			if (!bc.merged)bc.contactList.clear();
           	if (bc.updatedThisTimeStep) {
           		savedBodyContacts.add(bc);
           		bc.updatedThisTimeStep = false;
           	}
           }
   		bodyContacts.clear();
   		bodyContacts.addAll(savedBodyContacts);
   		      	
   		
	}
	/*
     * remem
     */
    private void rememberChildrenBodyContacts(RigidCollection b) {
		for (RigidBody body: b.collectionBodies){
			ArrayList<BodyContact> new_body_contact_list = new ArrayList<BodyContact>();
			for (BodyContact c: body.bodyContactList) {
    			c.body2.visited = false;
    		
    			if(c.updatedThisTimeStep){
    				new_body_contact_list.add(c);
    				c.updatedThisTimeStep = false;
    			}
    		}
			
			//also keep contact between two bodies that have been in contact before.
    		b.bodyContactList.clear();
    		b.bodyContactList.addAll(new_body_contact_list);
    		
		}
		
	}
	
    
	private void organize() {
		for (Contact c : contacts) {
			c.index = contacts.indexOf(c);
		}
		
	}
	

	public void PGS(double dt, double now) {
	   double mu = friction.getValue();
	   DenseVector lamda = new DenseVector(2*contacts.size());
	   lamda.zero();
	   int iteration = iterations.getValue();
	   int i = 0;
	   organize();
	   if(shuffle.getValue())knuth_shuffle();
	
	   if (warm_start.getValue()) {
		   for (Contact contact_i : contacts) {
		        	
		        	DenseVector j_1 = new DenseVector(contact_i.j_1);
		    		DenseVector j_2 = new DenseVector(contact_i.j_2);
		    		Block block1 = contact_i.block1;
		    		Block block2 = contact_i.block2;
		    		
					if(last_timestep_map.containsKey("contact:" + Integer.toString(block1.hashCode()) + "_" + Integer.toString(block2.hashCode() ))|| last_timestep_map.containsKey("contact:" + Integer.toString(block2.hashCode()) + "_" + Integer.toString(block1.hashCode() ))) {
						
						double m1inv = contact_i.body1.minv; 
						double m2inv = contact_i.body2.minv;
						double j1inv = contact_i.body1.jinv;
						double j2inv = contact_i.body2.jinv;
			
						Contact c = last_timestep_map.get("contact:" + Integer.toString(block1.hashCode()) + "_" + Integer.toString(block2.hashCode()));
						if(last_timestep_map.containsKey("contact:" + Integer.toString(block2.hashCode()) + "_" + Integer.toString(block1.hashCode() )))
						c = last_timestep_map.get("contact:" + Integer.toString(block2.hashCode()) + "_" + Integer.toString(block1.hashCode()));
						//if the old map contains this key, then get the lamda of the old map
						
						if (c.body1 != contact_i.body1 || c.body2 != contact_i.body2) {
							continue;
						}
						double old_lamda_n = c.lamda.x;
						double old_lamda_t = c.lamda.y;
						double old_delta_lamda_n = old_lamda_n;
						double old_delta_lamda_t = old_lamda_t;
						//set this lamda to the old lamda
						lamda.set(2*contact_i.index, old_lamda_n);
						lamda.set(2*contact_i.index + 1, old_lamda_t);
						//recompute Delta V's
						
						//first recompute t.
						
						double t_1_x_n = 0, t_1_y_n =0 , t_1_omega_n=0, t_2_x_n=0, t_2_y_n=0, t_2_omega_n = 0;
						double t_1_x_t=0, t_1_y_t=0, t_1_omega_t=0, t_2_x_t=0, t_2_y_t=0, t_2_omega_t = 0;
						        			//first body
			    		t_1_x_n = j_1.get(0) * m1inv*old_delta_lamda_n;
			    		t_1_y_n = j_1.get(1)* m1inv*old_delta_lamda_n;
			    		t_1_omega_n = j_1.get(2)* j1inv*old_delta_lamda_n;
			    			//second body
			    		t_2_x_n = j_1.get(3) * m2inv*old_delta_lamda_n;
			    		t_2_y_n = j_1.get(4) * m2inv*old_delta_lamda_n;
			    		t_2_omega_n = j_1.get(5) * j2inv*old_delta_lamda_n;
			    		
			    			//first body
			    		t_1_x_t = j_2.get(0) * m1inv*old_delta_lamda_t;
			    		t_1_y_t =  j_2.get(1) * m1inv*old_delta_lamda_t;
			    		t_1_omega_t =  j_2.get(2)  * j1inv*old_delta_lamda_t;
			    			//second body
			    		t_2_x_t =  j_2.get(3)  * m2inv*old_delta_lamda_t;
			    		t_2_y_t =  j_2.get(4)  * m2inv*old_delta_lamda_t;
			    		t_2_omega_t =  j_2.get(5) * j2inv* old_delta_lamda_t;
			    		
			    		//update delta V;
			    	
			    		DenseVector dV1 = contact_i.body1.delta_V; 
			    		DenseVector dV2 = contact_i.body2.delta_V; 
			    		dV1.set( 0, dV1.get( 0) + t_1_x_n  + t_1_x_t);
			    		dV1.set( 1, dV1.get( 1) + t_1_y_n + t_1_y_t );
			    		dV1.set(2, dV1.get(2) +  t_1_omega_n + t_1_omega_t);
			    		
			    		//update delta V;
			    		dV2.set(0, dV2.get(0) + t_2_x_n + t_2_x_t);
			    		dV2.set(1, dV2.get(1) + t_2_y_n + t_2_y_t );
			    		dV2.set(2, dV2.get(2) + t_2_omega_n + t_2_omega_t );
					}
		   	}
	    } 
	  
	       while(iteration > 0) {
	        	//shuffle for stability
	        	
	        	 for (i = 0; i < 2*contacts.size(); i++) {
	        		
	        		//if we are looking at a normal component of lamda
	        		double lamda_i = lamda.get(i);
	        		
	        		
	        		Contact contact_i = contacts.get(i/2);
	        		DenseVector j_1 = new DenseVector(contact_i.j_1);
	        		DenseVector j_2 = new DenseVector(contact_i.j_2);
	        		//
	        		
	        		double m1inv = contact_i.body1.minv; 
					double m2inv = contact_i.body2.minv;
					double j1inv =contact_i.body1.jinv;
					double j2inv = contact_i.body2.jinv;
		
	        		//calculate D_i_i 
	        		double d_i = 0;
	        		//first body component
	        		if (i%2 == 0) {
	            		d_i+= Math.pow(j_1.get(0), 2) * m1inv;
	            		d_i+= Math.pow(j_1.get(1), 2) * m1inv;
	            		d_i+= Math.pow(j_1.get(2), 2) * j1inv;
	            		//second body component
	            		d_i+= Math.pow(j_1.get(3), 2) * m2inv;
	            		d_i+= Math.pow(j_1.get(4), 2) * m2inv;
	            		d_i+= Math.pow(j_1.get(5), 2) * j2inv;
	        		}else { //tangential component
	        			//first body componenent
	        			d_i+= Math.pow(j_2.get(0), 2) * m1inv;
	            		d_i+= Math.pow(j_2.get(1), 2) * m1inv;
	            		d_i+= Math.pow(j_2.get(2), 2) * j1inv; 
	            		//second body component
	            		d_i+= Math.pow(j_2.get(3), 2) * m2inv;
	            		d_i+= Math.pow(j_2.get(4), 2) * m2inv;
	            		d_i+= Math.pow(j_2.get(5), 2) * j2inv;
	        		}
	        		
	        		//get J Row i Delta V term first
	        		//multiply the 6 J values with the appropriate delta V values
	        	
	        		DenseVector dV1 = contact_i.body1.delta_V; 
	        		DenseVector dV2 = contact_i.body2.delta_V; 
	        		double j_row_i_delta_V = 0;
	        		
	        		if (i%2 == 0) {
	        			//first body
	            		j_row_i_delta_V += j_1.get(0) * dV1.get(0);
	            		j_row_i_delta_V += j_1.get(1) * dV1.get(1);
	            		j_row_i_delta_V += j_1.get(2) * dV1.get(2);
	        	
		            	//second body
	            		j_row_i_delta_V +=  j_1.get(3) * dV2.get(0);
	            		j_row_i_delta_V +=  j_1.get(4) * dV2.get(1);
	            		j_row_i_delta_V +=  j_1.get(5) * dV2.get(2);
	            		
	        		}else {
	        			j_row_i_delta_V += j_2.get(0) * dV1.get(0);
	            		j_row_i_delta_V += j_2.get(1) * dV1.get(1);
	            		j_row_i_delta_V += j_2.get(2) * dV1.get(2);
	        	
		            	//second body
	            		j_row_i_delta_V += j_2.get(3)* dV2.get(0);
	            		j_row_i_delta_V += j_2.get(4) * dV2.get(1);
	            		j_row_i_delta_V += j_2.get(5) * dV2.get(2);
	           
	        		}
	        		j_row_i_delta_V /= d_i;
	        		
	        		//now take care of assembling b
	        		// find all relevant values of u.
	        		double u_1_x = contact_i.body1.v.x;
	        		double u_1_y = contact_i.body1.v.y;
	        		double u_1_omega = contact_i.body1.omega;
	        		
	        		double u_2_x = contact_i.body2.v.x;
	        		double u_2_y = contact_i.body2.v.y;
	        		double u_2_omega = contact_i.body2.omega;
	        		
	        		//add all relevant values of f, multiplied by appropriate minv to u_1_x etc
	        		u_1_x += contact_i.body1.force.x * m1inv*dt;
	        		u_1_y += contact_i.body1.force.y * m1inv*dt;
	        		u_1_omega += contact_i.body1.torque * j1inv*dt;
	        		
	        		u_2_x += contact_i.body2.force.x*m2inv*dt;
	        		u_2_y += contact_i.body2.force.y*m2inv*dt;
	        		u_2_omega += contact_i.body2.torque*j2inv*dt;
	        		
	        		//multiply all the u values by the appropriate J values.
	        		if (i%2 == 0) {
	            		u_1_x = u_1_x *( j_1.get(0));
	            		u_1_y = u_1_y * (j_1.get(1));
	            		u_1_omega = u_1_omega * (j_1.get(2));
	            		
	            		u_2_x = u_2_x *j_1.get(3);
	            		u_2_y =   u_2_y * j_1.get(4);
	            		u_2_omega =  u_2_omega *j_1.get(5);
	        		}else {
	        			u_1_x = u_1_x *(j_2.get(0));
	            		u_1_y = u_1_y * j_2.get(1);
	            		u_1_omega = u_1_omega * j_2.get(2);
	            		
	            		u_2_x =  u_2_x *j_2.get(3);
	            		u_2_y =  u_2_y *j_2.get(4);
	            		u_2_omega =  u_2_omega * j_2.get(5);
	        		}
	        		
	        		//add the Bounce vector to the u's over here, but don't need to do that just yet
	        		// bounce bounce bounce bounce bounce bounce bounce bounce bounce bounce ///
	        	
	        		// calculate Baumgarte Feedback (overlap of the two bodies)
	        		double c = this.feedback_stiffness.getValue();
	        		double bf = c*contact_i.constraint_violation;
	
	        		//putting b together.
	          		double b = 0;
	        		if (i%2 ==0) {
	        			b = (u_1_x + u_2_x + u_1_y + u_2_y + u_1_omega + u_2_omega - restitution.getValue() + bf)/d_i;
	        		}else{
	        			b = (u_1_x + u_2_x + u_1_y + u_2_y + u_1_omega + u_2_omega)/d_i;
	        		}
	        		
	        		double prev_lamda = lamda_i;
	        		//putting everything  for lamda together
	        		lamda_i= prev_lamda-b - j_row_i_delta_V;
	      
	    			
	        		if (i%2 == 0) {
	        			lamda_i = Math.max(0, lamda_i);
	        		}
	        		else {
	        			//tangential lamda, constrained by mu* normal lamda
	        			//get previous normal value for lamda
	        			double normal_lamda = lamda.get(i - 1);
	        			lamda_i = Math.max(lamda_i, -mu * normal_lamda);
	        			lamda_i = Math.min(lamda_i, mu*normal_lamda);
	        			
	        		}
	        		double delta_lamda = lamda_i - prev_lamda;
	        		//update the force on each body to account for the collision
	        		
	
	        		//updating lamda vector
	        		if (i%2 ==0) {
	        			
	        			lamda.set(2*contact_i.index,  lamda_i);
	        			contact_i.lamda.set(lamda_i, contact_i.lamda.y);
	        		}else {
	        			lamda.set(2*contact_i.index + 1, lamda_i);
	        			contact_i.lamda.set(contact_i.lamda.x, lamda_i);
	        		}
	        		//Now we still need to do the velocity update.
	        		// for that we must compute T = MinvJT TIMES deltaLamda
	        		double t_1_x, t_1_y, t_1_omega, t_2_x, t_2_y, t_2_omega = 0;
	        		if (i%2 == 0) {
	        			//first body
	        			t_1_x = j_1.get(0) * m1inv*delta_lamda;
	        			t_1_y = j_1.get(1)* m1inv*delta_lamda;
	        			t_1_omega = j_1.get(2)* j1inv*delta_lamda;
	        			//second body
	        			t_2_x = j_1.get(3) * m2inv*delta_lamda;
	        			t_2_y = j_1.get(4) * m2inv*delta_lamda;
	        			t_2_omega = j_1.get(5) * j2inv*delta_lamda;
	        		}else {
	        			//first body
	        			t_1_x = j_2.get(0) * m1inv*delta_lamda;
	        			t_1_y =  j_2.get(1) * m1inv*delta_lamda;
	        			t_1_omega =  j_2.get(2)  * j1inv*delta_lamda;
	        			//second body
	        			t_2_x =  j_2.get(3)  * m2inv*delta_lamda;
	        			t_2_y =  j_2.get(4)  * m2inv*delta_lamda;
	        			t_2_omega =  j_2.get(5) * j2inv*delta_lamda;
	        		}
	        		
	        		//update delta V;
	        		dV1.set( 0, dV1.get(0) + t_1_x );
	        		dV1.set( 1, dV1.get(1) + t_1_y );
	        		dV1.set( 2, dV1.get(2) + t_1_omega );
	        		
	        		//update delta V;
	        		dV2.set( 0, dV2.get(0) + t_2_x );
	        		dV2.set( 1, dV2.get(1) + t_2_y );
	        		dV2.set( 2, dV2.get(2) + t_2_omega );
	        		
	        	}
	        	iteration--;
	        	
	        }
	       
	       //calculatecontactForce
		   	Vector2d cForce = new Vector2d();
			double cTorque= 0;
		    for (Contact c: contacts) {
		    	cForce.set(c.lamda.x*c.j_1.get(0) + c.lamda.y*c.j_2.get(0),c.lamda.x*c.j_1.get(1) + c.lamda.y*c.j_2.get(1) );
		    	cTorque = c.lamda.x*c.j_1.get(2) + c.lamda.y*c.j_2.get(2);
		    	cForce.scale(1/dt);
		    	c.subBody1.transformW2B.transform(cForce);
		    	c.contactForceB1.set(cForce);
		    
		    	c.contactTorqueB1 = cTorque/dt;
		    	
		    	c.body1ContactForceHistory.add(c.contactForceB1);
		    	c.body1ContactTorqueHistory.add(c.contactTorqueB1);
		    	if (c.body1ContactForceHistory.size() > CollisionProcessor.sleep_accum.getValue()) {
		    		c.body1ContactForceHistory.remove(0);
		    		c.body1ContactTorqueHistory.remove(0);
		    	}
		    	
		    	c.body2ContactForceHistory.add(c.contactForceB2);
		    	c.body2ContactTorqueHistory.add(c.contactTorqueB2);
		    	if (c.body2ContactForceHistory.size() > CollisionProcessor.sleep_accum.getValue()) {
		    		c.body2ContactForceHistory.remove(0);
		    		c.body2ContactTorqueHistory.remove(0);
		    	}
		    	
		    	//if Body1 is a parent, also apply the contact force to the appropriate subBody
		 
		    	cForce.set(c.lamda.x*c.j_1.get(3) + c.lamda.y*c.j_2.get(3),c.lamda.x*c.j_1.get(4) + c.lamda.y*c.j_2.get(4) );
		    	cTorque = c.lamda.x*c.j_1.get(5) + c.lamda.y*c.j_2.get(5);
		    	cForce.scale(1/dt);
		    	c.subBody2.transformW2B.transform(cForce);
		    	c.contactForceB2.set(cForce);
		    	
		    	c.contactTorqueB2 = cTorque/dt;
		    
		    	
		    	//if Body2 is a parent, also apply the contact force to the appropriate subBody
			}
		    

	
	       //fill the new map
	       last_timestep_map.clear();
	        for (Contact co : contacts) {
	        	Block block1 = co.block1;
	        	Block block2 = co.block2;
	        
	        	last_timestep_map.put("contact:" + Integer.toString(block1.hashCode()) + "_" + Integer.toString(block2.hashCode()), co);
	        	
	
	        } 

      
       
    
    collisionSolveTime = (System.nanoTime() - now) * 1e-9;
}

	private void knuth_shuffle() {
		//go through each element in contacts 2.
    	//at each element, swap it for another random member of contacts2. 
    	//at each element, get a random index from i to contacts.size.
    	//swap this element with that element at that index. 
    	// go to next element in contacts 2
    	
		Collections.shuffle(contacts);
		for (Contact c : contacts) {
			c.index = contacts.indexOf(c);
		}
		
	}

	/**
     * Checks for collisions between bodies.  Note that you can optionaly implement some broad
     * phase test such as spatial hashing to reduce the n squared body-body tests.
     * Currently this does the naive n squared collision check.
     */
    private void broadPhase() {
        // Naive n squared body test.. might not be that bad for small number of bodies 
        visitID++;
        for ( RigidBody b1 : bodies ) {
            for ( RigidBody b2 : bodies ) { // not so inefficient given the continue on the next line
                if ( b1.index >= b2.index ) continue;
                if ( b1.pinned && b2.pinned ) continue;                
                narrowPhase( b1, b2 );                
            }
        }        
    }
    
    /**
     * Checks for collision between boundary blocks on two rigid bodies.
     * TODO: Objective 2: This needs to be improved as the n-squared block test is too slow!
     * @param body1
     * @param body2
     */
    private void narrowPhase( RigidBody body1, RigidBody body2 ) {
    	if(CollisionProcessor.useAdaptiveHamiltonian.getValue() || CollisionProcessor.use_contact_graph.getValue()) {
        	//if both bodies are inactive, they do not collide
        	if ((body1.active==2 && body2.active==2)) {
        		return;
        	}
    	}
        if ( ! useBVTree.getValue() ) {
            for ( Block b1 : body1.blocks ) {
                for ( Block b2 : body2.blocks ) {
                    processCollision( body1, b1, body2, b2 );
                }
            }
        } else {
        	if (body1 instanceof RigidCollection || body2 instanceof RigidCollection) {
        		narrowCollection(body1, body2);
        	}
        	else {
        		findCollisions(body1.root, body2.root, body1, body2);
        	}
	        
	  
            
        	
        }
    }
    /*
     * Recursive method that makes us check for collisions with each body in a rigidCollection
     */
    private void narrowCollection(RigidBody body1, RigidBody body2) {
		if (body1 instanceof RigidCollection && body2 instanceof RigidCollection) {
			for (RigidBody b: ((RigidCollection) body1).collectionBodies) {
				narrowCollection(b, body2);
			}
		}
			else if (body1 instanceof RigidCollection) {
				for (RigidBody b: ((RigidCollection) body1).collectionBodies) {
					findCollisions(b.root, body2.root, b, body2);
				}
				
			}	else if (body2 instanceof RigidCollection) {
				for (RigidBody b: ((RigidCollection) body2).collectionBodies) {
					findCollisions(body1.root, b.root, body1, b);
				}
			}
		
		
		//if both bodies are not rigid collections
		
		
	}
	//Recurses through all of body_1,then body_2
    private void findCollisions(BVNode body_1, BVNode body_2, RigidBody body1, RigidBody body2) {
     	if(body_1.visitID != visitID) {
    		body_1.visitID = visitID;
    		body_1.boundingDisc.updatecW();
    	}
    	if (body_2.visitID != visitID) {
    		body_2.visitID = visitID;
    		body_2.boundingDisc.updatecW();
    	}
    	
    	if (body_1.boundingDisc.intersects(body_2.boundingDisc)) {
	    	if (body_1.isLeaf() && body_2.isLeaf()) {
				Block leafBlock_1 = body_1.leafBlock;
				Block leafBlock_2 = body_2.leafBlock;
				
		
				
				processCollision(body1, leafBlock_1, body2, leafBlock_2);
				
				
				if (use_contact_graph.getValue()){
					 if (!body1.woken_up && body1.active == 0 && !body1.pinned && body2.active == 2) {
						
						 wake_neighbors(body2, collision_wake.getValue());
					}
					 else if (!body2.woken_up && body2.active == 0 && !body2.pinned && body1.active ==2) {
						
						wake_neighbors(body1, collision_wake.getValue());
					}
				
					
					}
			
				}
	    	else if(body_1.isLeaf()|| body_1.boundingDisc.r <= body_2.boundingDisc.r){
	    		//if theys overlap, and body 1 is either a leaf or smaller than body_2, break down body_2
	    		
		    		findCollisions(body_1, body_2.child1, body1, body2);
		    		findCollisions(body_1, body_2.child2, body1, body2);
	    		
	    		}
	    	else if(body_2.isLeaf() || body_2.boundingDisc.r <= body_1.boundingDisc.r) {
	    		//if they overlap, and body 2 is either a leaf or smaller than body_1, break down body_1
	    		
		    		findCollisions(body_1.child1, body_2, body1, body2);
		    		findCollisions(body_1.child2, body_2, body1, body2);
	    		}
    	}
		
		
	}

/** 
 * if a collision is detected, will travel the contact graph and wake up 
 * bodies that are n hops away in the contact graph
 * @param body1
 * @param body2
 */
	private void wake_neighbors(RigidBody body1, int hop) {
		if (hop > 0) {
			body1.active = 0;
			/*if (!body1.active_past.isEmpty()) {
				body1.active_past.remove(body1.active_past.size() - 1);} */
			hop--;
			body1.visited = true;
			body1.woken_up = true;
		//	body1.active_past.add(true); makes bodies oscillate between sleeping and waking
			for (BodyContact c: body1.bodyContactList) {
					if (!c.body2.pinned)
					wake_neighbors(c.body2, hop);
				
				
			}
		}
	
		
	}

	/** 
     * The visitID is used to tag boundary volumes that are visited in 
     * a given time step.  Marking boundary volume nodes as visited during
     * a time step allows for a visualization of those used, but it can also
     * be used to more efficiently update the centeres of bounding volumes
     * (i.e., call a BVNode's updatecW method at most once on any given timestep)
     */
    int visitID = 0;
    
    
    /**
     * Resets the state of the collision processor by clearing all
     * currently identified contacts, and reseting the visitID for
     * tracking the bounding volumes used
     */
    public void reset() {
        contacts.clear();
        Contact.nextContactIndex = 0;
        visitID = 0;            
    }
    
    // some working variables for processing collisions
    private Point2d tmp1 = new Point2d();
    private Point2d tmp2 = new Point2d();
    private Point2d contactW = new Point2d();
    private Vector2d force = new Vector2d();
    private Vector2d contactV1 = new Vector2d();
    private Vector2d contactV2 = new Vector2d();
    private Vector2d relativeVelocity = new Vector2d();
    private Vector2d normal = new Vector2d();
        
    
    
    /**
     * Processes a collision between two bodies for two given blocks that are colliding.
     * Currently this implements a penalty force
     * @param body1
     * @param b1
     * @param body2
     * @param b2
     */
    private void processCollision( RigidBody body1, Block b1, RigidBody body2, Block b2 ) {        
        double k = contactSpringStiffness.getValue();
        double c1 = contactSpringDamping.getValue();
        double threshold = separationVelocityThreshold.getValue();
        boolean useSpring = enableContactSpring.getValue();
        boolean useDamping = enableContactDamping.getValue();

        body1.transformB2W.transform( b1.pB, tmp1 );
        body2.transformB2W.transform( b2.pB, tmp2 );
        double distance = tmp1.distance(tmp2);
        
        if ( distance < Block.radius * 2 ) {
            // contact point at halfway between points 
            // NOTE: this assumes that the two blocks have the same radius!
            contactW.interpolate( tmp1, tmp2, .5 );
            // contact normal
            normal.sub( tmp2, tmp1 );
            normal.normalize();
            // create the contact
            Contact contact = null;
            
            
            /*
             *  very important here... the Contact will contain information on contact location, 
             *  where if any of the bodies involved are parts of a collection, then the contact 
             *  will list the collection as one of its bodies... not the actual contacting subbody
            */
            if (body1.parent != null && body2.parent != null) {
            	contact = new Contact( body1.parent, body2.parent, contactW, normal, b1, b2, distance, body1, body2);
            }
            else if (body1.parent != null) {
               contact = new Contact( body1.parent, body2, contactW, normal, b1, b2, distance,  body1, body2);
            }else if (body2.parent != null) {
            	contact = new Contact( body1, body2.parent, contactW, normal, b1, b2, distance,  body1, body2);
            }else {
            	contact = new Contact( body1, body2, contactW, normal, b1, b2, distance,  body1, body2);
            }
            
            //set normals in body coordinates
            contact.normalB1.set(normal);
            contact.normalB2.set(normal);
            body1.transformW2B.transform(contact.normalB1);
            
            body2.transformW2B.transform(contact.normalB2);
            contact.normalB2.scale(-1);
            
            // simple option... add to contact list...
            contacts.add( contact );
           
                 
            //that being said... the BODYCONTACTS bodies will only ever be subBodies or 
            // unmerged normal rigid bodies... they will never be a collection.
            
            if (RigidBodySystem.enableMerging.getValue()&&  (!body1.pinned || !body2.pinned)) {
            	
            	//check if this body contact exists already
            	BodyContact bc = BodyContact.checkExists(body1, body2, bodyContacts);
            	if (bc != null) { //if it exists
               		if (!bc.updatedThisTimeStep) {//only once per timestep
               			bc.relativeVelHistory.add(contact.getRelativeMetric());
               			if (bc.relativeVelHistory.size() > CollisionProcessor.sleep_accum.getValue()) {
		            		bc.relativeVelHistory.remove(0);
		            	 }
               			bc.updatedThisTimeStep = true;
               	
               		}
            	}else {
            		//body contact did not exist in previous list
            		bc = new BodyContact(body1, body2);
            		bc.relativeVelHistory.add(contact.getRelativeMetric());
            		bc.updatedThisTimeStep = true;
            		bodyContacts.add(bc);
            		
            		if (!body1.bodyContactList.contains(bc))
            		body1.bodyContactList.add(bc);
            		if (!body2.bodyContactList.contains(bc))
    	            body2.bodyContactList.add(bc);
            	}
            	contact.bc = bc;
            	bc.contactList.add(contact);
            
	    
	            
            
            }
            
            
            if ( !doLCP.getValue() ) {
                // compute relative body velocity at contact point
                body1.getSpatialVelocity( contactW, contactV1 );
                body2.getSpatialVelocity( contactW, contactV2 );
                relativeVelocity.sub( contactV1, contactV2 );
                if ( -relativeVelocity.dot( normal ) < threshold ) {
                    if ( useSpring ) {
                        // spring force
                        double interpenetration =  Block.radius * 2 - distance; // a negative quantity
                        force.scale( interpenetration * k, normal );
                        body2.applyContactForceW(contactW, force);
                        force.scale(-1);
                        body1.applyContactForceW(contactW, force);
                    }
                    if ( useDamping ) {
                        // spring damping forces!
                        // vertical
                        force.scale( relativeVelocity.dot(normal) * c1, normal );                    
                        body2.applyContactForceW( contactW, force );
                        force.scale(-1);
                        body1.applyContactForceW( contactW, force );
                    }
                }
            }
           }
            
    }
   



	/** Stiffness of the contact penalty spring */
    private DoubleParameter contactSpringStiffness = new DoubleParameter("penalty contact stiffness", 1e3, 1, 1e5 );
    

    /** Viscous damping coefficient for the contact penalty spring */
    private DoubleParameter contactSpringDamping = new DoubleParameter("penalty contact damping", 10, 1, 1e4 );
    
    /** Threshold for the relative velocity in the normal direction, for determining if spring force will be applied. */
    private DoubleParameter separationVelocityThreshold = new DoubleParameter( "penalty separation velocity threshold (controls bounce)", 1e-9, 1e-9, 1e3 );
    
    /** Enables the contact penalty spring */
    private BooleanParameter enableContactSpring = new BooleanParameter("enable penalty contact spring", true );
    
    /** Enables damping of the contact penalty spring */
    private BooleanParameter enableContactDamping = new BooleanParameter("enable penalty contact damping", true );
    
    /** Restitution parameter for contact constraints */
    public DoubleParameter restitution = new DoubleParameter( "restitution (bounce)", 0, 0, 1 );
    
    /** Coulomb friction coefficient for contact constraint */
    public DoubleParameter friction = new DoubleParameter("Coulomb friction", 0.33, 0, 2 );
    
    /** Number of iterations to use in projected Gauss Seidel solve */
    public IntParameter iterations = new IntParameter("iterations for GS solve", 70, 1, 500);
    
    /** Flag for switching between penalty based contact and contact constraints */
    private BooleanParameter doLCP = new BooleanParameter( "do LCP solve", true );
   
    /** Flag for using shuffle */
    private BooleanParameter shuffle = new BooleanParameter( "shuffle", false);
    /** Flag for using shuffle */
    private BooleanParameter warm_start = new BooleanParameter( "warm start", true);
    
    /** Flag for enabling the use of hierarchical collision detection for body pairs */
    private BooleanParameter useBVTree = new BooleanParameter( "use BVTree", true);
   
    
    public static DoubleParameter feedback_stiffness = new DoubleParameter("feedback coefficient", 0, 0,50  );
   
    /** toggle on or off adaptive hamiltonian*/
    public static BooleanParameter  useAdaptiveHamiltonian = new BooleanParameter("enable use of adaptive hamiltonian", false );
    
    public static DoubleParameter sleepingThreshold = new DoubleParameter("sleeping threshold", 1.0, 0, 10 );
    
    public static DoubleParameter wakingThreshold = new DoubleParameter("waking threshold", 15, 0, 30);

    
    public static BooleanParameter  use_contact_graph = new BooleanParameter("enable use of contact graph heuristic", false );
    
    public static DoubleParameter impulseTolerance = new DoubleParameter("force metric tolerance", 2.5, 0, 15);
    
    public static IntParameter collision_wake = new IntParameter("wake n neighbors", 2, 0, 10 );
    
    public static IntParameter sleep_accum = new IntParameter("accumulate N sleep querie", 50, 0, 200 );
    
    
    /**
     * @return controls for the collision processor
     */
    public JPanel getControls() {
        VerticalFlowPanel vfp = new VerticalFlowPanel();
        vfp.setBorder( new TitledBorder("Collision Processing Controls") );
        vfp.add( useBVTree.getControls() );
        vfp.add( doLCP.getControls() );
        vfp.add( shuffle.getControls() );
        vfp.add( warm_start.getControls() );
        vfp.add( iterations.getSliderControls() );
        vfp.add( restitution.getSliderControls(false) );
        vfp.add( friction.getSliderControls(false) );
        vfp.add( feedback_stiffness.getSliderControls(false) );
       
        vfp.add( sleepingThreshold.getSliderControls(false) );
        vfp.add( useAdaptiveHamiltonian.getControls() );
       
        vfp.add( wakingThreshold.getSliderControls(false) );
        
        vfp.add( use_contact_graph.getControls() );
        vfp.add( impulseTolerance.getSliderControls(false) );
        vfp.add(collision_wake.getSliderControls());
        vfp.add(sleep_accum.getSliderControls());
        VerticalFlowPanel vfp2 = new VerticalFlowPanel();
        vfp2.setBorder( new TitledBorder("penalty method controls") );

        vfp2.add( contactSpringStiffness.getSliderControls(true) );
        vfp2.add( contactSpringDamping.getSliderControls(true) );
        vfp2.add( separationVelocityThreshold.getSliderControls( true ) );
        vfp2.add( enableContactDamping.getControls() );
        vfp2.add( enableContactSpring.getControls() );
        
        CollapsiblePanel cp = new CollapsiblePanel(vfp2.getPanel());
        cp.collapse();
        vfp.add( cp );        
        return vfp.getPanel();
    }
    
}
