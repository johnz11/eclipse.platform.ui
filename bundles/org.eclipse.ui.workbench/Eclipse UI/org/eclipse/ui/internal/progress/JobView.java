package org.eclipse.ui.internal.progress;

import java.net.URL;
import java.util.HashSet;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.internal.misc.Assert;
import org.eclipse.ui.part.*;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.*;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.util.ListenerList;
import org.eclipse.swt.SWT;


public class JobView extends ViewPart {
	
	static final String PROPERTY_PREFIX= "org.eclipse.ui.workbench.progress"; //$NON-NLS-1$

	/* an property of type URL that specifies the icon to use for this job. */
	static final String PROPERTY_ICON= "icon"; //$NON-NLS-1$
	/* this Boolean property controls whether a finished job is kept in the list. */
	static final String PROPERTY_KEEP= "keep"; //$NON-NLS-1$
	/* an property of type IAction that is run when link is activated. */
	static final String PROPERTY_GOTO= "goto"; //$NON-NLS-1$

	
	/*
	 * Label with hyperlink capability.
	 */
	class Hyperlink extends Canvas implements Listener {
		final static int MARGINWIDTH = 1;
		final static int MARGINHEIGHT = 1;
		boolean hasFocus;
		String text;
		boolean underlined;
		IAction gotoAction;
		
		Hyperlink(Composite parent, int flags) {
			super(parent, SWT.NO_BACKGROUND | flags);
			addListener(SWT.KeyDown, this);
			addListener(SWT.Paint, this);
			addListener(SWT.MouseEnter, this);
			addListener(SWT.MouseExit, this);
			addListener(SWT.MouseUp, this);
			addListener(SWT.FocusIn, this);
			addListener(SWT.FocusOut, this);
		}
		public void handleEvent(Event e) {
			switch (e.type) {
			case SWT.KeyDown:
				if (e.character == '\r')
					handleActivate();
				break;
			case SWT.Paint:
				paint(e.gc);
				break;
			case SWT.FocusIn :
				hasFocus = true;
			case SWT.MouseEnter :
				if (underlined) {
					setForeground(linkColor2);
					redraw();
				}
				break;
			case SWT.FocusOut :
				hasFocus = false;
			case SWT.MouseExit :
				if (underlined) {
					setForeground(linkColor);
					redraw();
				}
				break;
			case SWT.DefaultSelection :
				handleActivate();
				break;
			case SWT.MouseUp :
				Point size= getSize();
				if (e.button != 1 || e.x < 0 || e.y < 0 || e.x >= size.x || e.y >= size.y)
					return;
				handleActivate();
				break;
			}
		}
		void setText(String t) {
			text= t != null ? t : ""; //$NON-NLS-1$
			redraw();
		}
		void setAction(IAction action) {
			gotoAction= action;
			underlined= action != null;
			setForeground(underlined ? linkColor : taskColor);
			if (underlined)
				setCursor(handCursor);
			redraw();
		}
		public Point computeSize(int wHint, int hHint, boolean changed) {
			checkWidget();
			int innerWidth= wHint;
			if (innerWidth != SWT.DEFAULT)
				innerWidth -= MARGINWIDTH * 2;
			GC gc= new GC(this);
			gc.setFont(getFont());
			Point extent= gc.textExtent(text);
			gc.dispose();
			return new Point(extent.x + 2 * MARGINWIDTH, extent.y + 2 * MARGINHEIGHT);
		}
		protected void paint(GC gc) {
			Rectangle clientArea= getClientArea();
			Image buffer= new Image(getDisplay(), clientArea.width, clientArea.height);
			buffer.setBackground(getBackground());
			GC bufferGC= new GC(buffer, gc.getStyle());
			bufferGC.setBackground(getBackground());
			bufferGC.fillRectangle(0, 0, clientArea.width, clientArea.height);
			bufferGC.setFont(getFont());
			bufferGC.setForeground(getForeground());
			bufferGC.drawText(text, MARGINWIDTH, MARGINHEIGHT, true);
			int sw= bufferGC.stringExtent(text).x;
			if (underlined) {
				FontMetrics fm= bufferGC.getFontMetrics();
				int lineY= clientArea.height - MARGINHEIGHT - fm.getDescent() + 1;
				bufferGC.drawLine(MARGINWIDTH, lineY, MARGINWIDTH + sw, lineY);
			}
			if (hasFocus)
				bufferGC.drawFocus(0, 0, sw, clientArea.height);
			gc.drawImage(buffer, 0, 0);
			bufferGC.dispose();
			buffer.dispose();
		}
		protected void handleActivate() {
			if (underlined && gotoAction != null && gotoAction.isEnabled())
				gotoAction.run();
		}
	}
		
	/*
	 * An SWT widget representing a JobModel
	 */
	class JobItem extends Composite {
		
		static final int MARGIN= 2;
		static final int HGAP= 7;
		static final int VGAP= 2;
		static final int MAX_PROGRESS_HEIGHT= 12;
		static final int MIN_ICON_SIZE= 16;

		private JobTreeElement jobTreeElement;
		boolean fKeep;
		boolean fTerminated;
		boolean fSelected;
		boolean fProgressIsShown;
		boolean fTaskIsShown;

		int fCachedWidth= -1;
		int fCachedHeight= -1;
		Label fIcon;
		Label fName;
		ProgressBar progressBar;
		Hyperlink fTask;
		Hyperlink fTask2;
		ToolBar actionBar;
		ToolItem fActionButton;
		ToolItem fGotoButton;
		

		JobItem(Composite parent, JobTreeElement info) {
			super(parent, SWT.NONE);
			
			Assert.isNotNull(info);
			jobTreeElement= info;
			
			Display display= getDisplay();

			Job job= getJob();
			IAction gotoAction= null;	
			Image image= null;
			if (job != null) {
				Object property= job.getProperty(new QualifiedName(PROPERTY_PREFIX, PROPERTY_KEEP));
				if (property instanceof Boolean)
					fKeep= ((Boolean)property).booleanValue();
				property= job.getProperty(new QualifiedName(PROPERTY_PREFIX, PROPERTY_GOTO));
				if (property instanceof IAction)
					gotoAction= (IAction) property;
				property= job.getProperty(new QualifiedName(PROPERTY_PREFIX, PROPERTY_ICON));
				if (property instanceof URL) {
					URL url= (URL) property;
					ImageDescriptor id= ImageDescriptor.createFromURL(url);
					image= id.createImage(display);
				}
			}
			
			MouseListener ml= new MouseAdapter() {
				public void mouseDown(MouseEvent e) {
//					select(JobItem.this);
				}
			};
			
			setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

			fIcon= new Label(this, SWT.NONE);
			if (image != null)
				fIcon.setImage(image);				
			fIcon.addMouseListener(ml);
			
			fName= new Label(this, SWT.NONE);
			//fName.setText(getName());
			fName.addMouseListener(ml);
			
			actionBar= new ToolBar(this, SWT.FLAT);
						
			if (false && gotoAction != null) {
				final IAction gotoAction2= gotoAction;
				fGotoButton= new ToolItem(actionBar, SWT.NONE);
				fGotoButton.setImage(getImage(parent.getDisplay(), "newprogress_goto.gif")); //$NON-NLS-1$
				fGotoButton.setToolTipText(gotoAction.getToolTipText());
				fGotoButton.setEnabled(gotoAction.isEnabled());
				fGotoButton.addSelectionListener(new SelectionAdapter() {
					public void widgetSelected(SelectionEvent e) {
						if (gotoAction2.isEnabled()) {
							gotoAction2.run();
							if (fTerminated)
								kill(true, true);
						}
					}
				});
			}

			fActionButton= new ToolItem(actionBar, SWT.NONE);
			fActionButton.setImage(getImage(parent.getDisplay(), "newprogress_cancel.gif")); //$NON-NLS-1$
			fActionButton.setToolTipText(ProgressMessages.getString("NewProgressView.CancelJobToolTip")); //$NON-NLS-1$
			fActionButton.addSelectionListener(new SelectionAdapter() {
				public void widgetSelected(SelectionEvent e) {
					fActionButton.setEnabled(false);
					if (fTerminated)
						kill(true, true);
					else
						jobTreeElement.cancel();
				}
			});
			
			actionBar.pack();

			fProgressIsShown= true;
			
			fTaskIsShown= true;
 			fTask= new Hyperlink(this, SWT.NONE);
 			if (gotoAction != null) {
 				fTask.setToolTipText(gotoAction.getToolTipText());
 				fTask.setAction(gotoAction);
 			}
			fTask.setText(getTaskName());
			
			addMouseListener(ml);
			
			addControlListener(new ControlAdapter() {
				public void controlResized(ControlEvent e) {
					handleResize();
				}
			});
		}
		
		Job getJob() {
			if (jobTreeElement instanceof JobInfo)
				return ((JobInfo)jobTreeElement).getJob();
			return null;
		}
		
		private String getStatus() {
			if (jobTreeElement instanceof JobInfo) {
				JobInfo ji= (JobInfo) jobTreeElement;
				if (ji.isCanceled())
					return "Canceled";
				if (ji.isBlocked())
					return "Blocked (" + ji.getBlockedStatus().getMessage() + ")";
				switch (ji.getJob().getState()) {
				case Job.NONE:
					return "Terminated";
				case Job.RUNNING:
					return "Running";
				case Job.SLEEPING:
					return "Sleeping";
				case Job.WAITING:
					return "Waiting";
				}
				return "Unknown state";
			}
			return "Group";
		}
		
		String getName() {
			
			if (jobTreeElement instanceof JobInfo) {
				JobInfo ji= (JobInfo) jobTreeElement;
				Job job= ji.getJob();
				if (job != null)
					return job.getName();
			}
			
			if (jobTreeElement instanceof GroupInfo) {
				GroupInfo gi= (GroupInfo) jobTreeElement;
				Object[] objects = gi.getChildren();
				if (objects.length > 0 && objects[0] instanceof JobTreeElement) {
					String s= ((JobTreeElement)objects[0]).getDisplayString();
					int pos= s.indexOf("%) "); //$NON-NLS-1$
					if (pos > 0)
						s= s.substring(pos+3);
					return s;
				}
			}
			
			return "??? " + jobTreeElement.getDisplayString();
		}
		
		String getTaskName() {
			
			if (jobTreeElement instanceof JobInfo) {
				JobInfo ji= (JobInfo) jobTreeElement;
				TaskInfo ti= ji.getTaskInfo();
				if (ti != null) {
					String s= ti.getDisplayString();
					String n= getName() + ": ";
					int pos= s.indexOf(n);
					if (pos > 0)
						s= s.substring(pos+n.length());
					return s;
				}
			}
			
			if (jobTreeElement instanceof GroupInfo) {
				GroupInfo gi= (GroupInfo) jobTreeElement;
				Object[] objects = gi.getChildren();
				if (objects.length > 0) {
					JobInfo ji= (JobInfo) objects[0];
					objects = ji.getChildren();
					if (objects.length > 0 && objects[0] instanceof JobTreeElement)
						return ((JobTreeElement)objects[0]).getDisplayString();
				}
			}
			return jobTreeElement.getDisplayString();
		}
	
		boolean remove() {
			fTerminated= true;
			if (fKeep) {
				boolean changed= false;
				if (progressBar != null && !progressBar.isDisposed()) {
					fProgressIsShown= false;
					progressBar.setVisible(false);
					changed= true;
				}
				if (!fActionButton.isDisposed()) {
					fActionButton.setImage(getImage(actionBar.getDisplay(), "newprogress_clear.gif")); //$NON-NLS-1$
					fActionButton.setToolTipText(ProgressMessages.getString("NewProgressView.RemoveJobToolTip")); //$NON-NLS-1$
					fActionButton.setEnabled(true);
				}
				refresh();
				return changed;
			}
			dispose();
			return true;	
		}
		
		boolean kill(boolean refresh, boolean broadcast) {
			if (fTerminated) {
				
				if (broadcast) {
					Object[] listeners= allJobViews.getListeners();
					for (int i= 0; i < listeners.length; i++) {
						JobView jv= (JobView) listeners[i];
						if (jv != JobView.this) {
							JobItem ji= jv.findJobItem("kill", null, jobTreeElement);
							if (ji != null)
								ji.kill(true, false);
						}
					}
				}
				
				dispose();
				relayout(refresh, refresh);
				return true;
			}
			return false;
		}
		
		void handleResize() {
			Point e= getSize();
			Point e1= fIcon.computeSize(SWT.DEFAULT, SWT.DEFAULT); e1.x= MIN_ICON_SIZE;
			Point e2= fName.computeSize(SWT.DEFAULT, SWT.DEFAULT);
			Point e4= fTask.computeSize(SWT.DEFAULT, SWT.DEFAULT);
			Point e5= actionBar.computeSize(SWT.DEFAULT, SWT.DEFAULT);
			
			int iw= e.x-MARGIN-HGAP-e5.x-MARGIN;
			int indent= 16+HGAP;
				
			int y= MARGIN;
			int h= Math.max(e1.y, e2.y);
			fIcon.setBounds(MARGIN, y+(h-e1.y)/2, e1.x, e1.y);
			fName.setBounds(MARGIN+e1.x+HGAP, y+(h-e2.y)/2, iw-e1.x-HGAP, e2.y);
			y+= h;
			if (fProgressIsShown && progressBar != null /* fProgressBar.isVisible() */) {
				Point e3= progressBar.computeSize(SWT.DEFAULT, SWT.DEFAULT); e3.y= MAX_PROGRESS_HEIGHT;
				y+= VGAP;
				progressBar.setBounds(MARGIN+indent, y, iw-indent, e3.y);
				y+= e3.y;
			}
			if (fTaskIsShown /* fTask.isVisible() */) {
				y+= VGAP;
				fTask.setBounds(MARGIN+indent, y, iw-indent, e4.y);
				y+= e4.y;
			}
			
			actionBar.setBounds(e.x-MARGIN-e5.x, (e.y-e5.y)/2, e5.x, e5.y);
		}
		
		public Point computeSize(int wHint, int hHint, boolean changed) {

			int w, h;
			
			if (changed || fCachedHeight <= 0 || fCachedWidth <= 0) {
				Point e1= fIcon.computeSize(SWT.DEFAULT, SWT.DEFAULT); e1.x= MIN_ICON_SIZE;
				Point e2= fName.computeSize(SWT.DEFAULT, SWT.DEFAULT);
				Point e4= fTask.computeSize(SWT.DEFAULT, SWT.DEFAULT);
				
				fCachedWidth= MARGIN + e1.x + HGAP + 100 + MARGIN;
					
				fCachedHeight= MARGIN + Math.max(e1.y, e2.y);
				if (fProgressIsShown && progressBar != null/* fProgressBar.isVisible() */) {
					Point e3= progressBar.computeSize(SWT.DEFAULT, SWT.DEFAULT); e3.y= MAX_PROGRESS_HEIGHT;
					fCachedHeight+= VGAP + e3.y;
				}
				if (fTaskIsShown /* fTask.isVisible() */)
					fCachedHeight+= VGAP + e4.y;
				fCachedHeight+= MARGIN;
			}
			
			w= wHint == SWT.DEFAULT ? fCachedWidth : wHint;
			h= hHint == SWT.DEFAULT ? fCachedHeight : hHint;
			
			return new Point(w, h);
		}
		
		/*
		 * Update the background colors.
		 */
		void updateBackground(boolean dark) {
			Color c;
			if (fSelected)
				c= selectedColor;				
			else
				c= dark ? darkColor : whiteColor;
			setBackground(c);				
			fIcon.setBackground(c);	
			fName.setBackground(c);
			fTask.setBackground(c);
			actionBar.setBackground(c);
		}
		
		/*
		 * Sets the progress.
		 */
		void setPercentDone(int percentDone) {
			if (percentDone >= 0 && percentDone < 100) {
				if (progressBar == null) {
					progressBar= new ProgressBar(this, SWT.HORIZONTAL);
					progressBar.setMaximum(100);
					progressBar.setSelection(percentDone);
					relayout(true, false);
				} else
					progressBar.setSelection(percentDone);
			}
		}
		
		/*
		 * Sets the task message.
		 */
		void setTask(String message) {
			if (fTask != null && !fTask.isDisposed())
				fTask.setText(message);
		}

		String getResult() {
			Job job= getJob();
			if (job != null) {
				IStatus result= job.getResult();
				if (result != null) {
					String m= result.getMessage();
					if (m != null && m.trim().length() > 0)
						return m;
				}
			}
			return null;
		}
		
		boolean isCanceled() {
			if (jobTreeElement instanceof JobInfo)
				return ((JobInfo)jobTreeElement).isCanceled();
			return false;
		}
		
		/*
		 * Update the visual item from the model.
		 */
		void refresh() {

			fName.setText(getName());

			if (fTerminated) {
				if (! isCanceled() && fKeep) {
					String message= getResult();
					if (message != null) {
						setTask(message);
						return;
					}
				}
			} else {
				fActionButton.setEnabled(true || jobTreeElement.isCancellable());				
			}
			
			if (jobTreeElement instanceof JobInfo) {
				TaskInfo ti= ((JobInfo)jobTreeElement).getTaskInfo();
				if (ti != null)
					setPercentDone(ti.getPercentDone());
			} else if (jobTreeElement instanceof GroupInfo) {
				GroupInfo gi= (GroupInfo) jobTreeElement;
				setPercentDone(gi.getPercentDone());		
			}
			
			setTask(getTaskName());
		}
	}
	
	private static ListenerList allJobViews= new ListenerList();
	
	private Color linkColor;
	private Color linkColor2;
	private Color darkColor;
	private Color whiteColor;
	private Color taskColor;
	private Color selectedColor;
	private Cursor handCursor;

	private Composite list;
	private ScrolledComposite scroller;
	private IAction clearAllAction;
	private IAction verboseAction;
	
	public JobView() {
	}
	
	/**
	 * This is a callback that will allow us to create the viewer and initialize it.
	 */
	public void createPartControl(Composite parent) {
		
		allJobViews.add(this);
		
		final IProgressUpdateCollector puc= new IProgressUpdateCollector() {
			public void refresh(Object[] elements) {
				refreshJobItems(elements);
			}
			public void add(Object[] elements) {
				addJobItems(elements);
			}
			public void remove(Object[] elements) {
				removeJobItems(elements);
			}
			public void refresh() {
				refreshAllJobItems();
			}
		};
		
		final ProgressViewUpdater pvu= ProgressViewUpdater.getSingleton();
		pvu.addCollector(puc);
		
		Display display= parent.getDisplay();
		handCursor= new Cursor(display, SWT.CURSOR_HAND);

		boolean carbon= "carbon".equals(SWT.getPlatform()); //$NON-NLS-1$
		whiteColor= display.getSystemColor(SWT.COLOR_WHITE);
		if (carbon)
			darkColor= new Color(display, 230, 230, 230);
		else
			darkColor= new Color(display, 245, 245, 245);
		taskColor= new Color(display, 120, 120, 120);
		selectedColor= display.getSystemColor(SWT.COLOR_LIST_SELECTION);
		linkColor= display.getSystemColor(SWT.COLOR_DARK_BLUE);
		linkColor2= display.getSystemColor(SWT.COLOR_BLUE);
				

		scroller= new ScrolledComposite(parent, SWT.H_SCROLL | SWT.V_SCROLL);
		int height= scroller.getFont().getFontData()[0].getHeight();
		scroller.getVerticalBar().setIncrement(height * 2);
		scroller.setExpandHorizontal(true);
		scroller.setExpandVertical(true);
				
		list= new Composite(scroller, SWT.NONE);
		list.setBackground(whiteColor);
		
		scroller.setContent(list);
		
		GridLayout layout= new GridLayout();
		layout.numColumns= 1;
		layout.marginHeight= layout.marginWidth= layout.verticalSpacing= 0;
		list.setLayout(layout);
		
		list.addDisposeListener(new DisposeListener() {
			public void widgetDisposed(DisposeEvent e) {
				pvu.removeCollector(puc);				
				allJobViews.remove(this);
			}
		});
		
		// build the actions
		clearAllAction= new Action() {
			public void run() {
				clearAll();
			}
		};
		clearAllAction.setText(ProgressMessages.getString("ProgressView.ClearAllAction")); //$NON-NLS-1$	
		clearAllAction.setToolTipText(ProgressMessages.getString("NewProgressView.RemoveAllJobsToolTip")); //$NON-NLS-1$	
		ImageDescriptor id= ImageDescriptor.createFromFile(JobView.class, "newprogress_clearall.gif"); //$NON-NLS-1$
		if (id != null)
			clearAllAction.setImageDescriptor(id);
		
		verboseAction= new Action(ProgressMessages.getString("ProgressView.VerboseAction"), IAction.AS_CHECK_BOX) { //$NON-NLS-1$
			public void run() {
				ProgressViewUpdater updater = ProgressViewUpdater.getSingleton();
				updater.debug = !updater.debug;
				setChecked(updater.debug);
				updater.refreshAll();
			}
		};
		verboseAction.setChecked(pvu.debug);

		// install the actions
		IActionBars bars= getViewSite().getActionBars();
		IMenuManager mm= bars.getMenuManager();
		mm.add(clearAllAction);
		mm.add(verboseAction);
		
		IToolBarManager tm= bars.getToolBarManager();
		tm.add(clearAllAction);

		// refresh UI
		refreshAllJobItems();
}
	
	private void refreshJobItems(Object[] elements) {
		
		//elements = getRoots(elements, true);

		Control[] children= list.getChildren();
		for (int i= 0; i < elements.length; i++) {
			JobTreeElement jte= (JobTreeElement) elements[i];
			if (use(jte)) {
				JobItem ji= findJobItem("refresh", children, jte);
				if (ji != null)
					ji.refresh();
			}
		}
	}
	
	private void addJobItems(Object[] elements) {
		boolean changed= false;
		Control[] children= list.getChildren();
		JobTreeElement lastAdded= null;
		for (int i= 0; i < elements.length; i++) {
			JobTreeElement jte= (JobTreeElement) elements[i];
			if (use(jte)) {
				if (findJobItem("add", children, jte) == null) {
					new JobItem(list, jte);
					changed= true;
					lastAdded= jte;
				}
			}
		}
		relayout(changed, changed);
		if (lastAdded != null)
			reveal(lastAdded);
	}
	
	private void removeJobItems(Object[] elements) {
		boolean changed= false;
		Control[] children= list.getChildren();
		for (int i= 0; i < elements.length; i++) {
			JobTreeElement jte= (JobTreeElement) elements[i];
			if (use(jte)) {
				JobItem ji= findJobItem("remove", children, jte);
				if (ji != null)
					changed |= ji.remove();
			}
		}
		relayout(changed, changed);
	}
	
	private void refreshAllJobItems() {
		boolean changed= false;
		JobTreeElement lastAdded= null;
		
		JobTreeElement[] roots= ProgressManager.getInstance().getRootElements(ProgressViewUpdater.getSingleton().debug);
		//roots= getDisplayedValues(roots);
		HashSet modelJobs= new HashSet();
		for (int z= 0; z < roots.length; z++)
			if (use(roots[z]))
				modelJobs.add(roots[z]);
		
		HashSet shownJobs= new HashSet();
		
		// find all removed
		Control[] children= list.getChildren();
		for (int i= 0; i < children.length; i++) {
			JobItem ji= (JobItem)children[i];
			JobTreeElement jte= ji.jobTreeElement;
			shownJobs.add(jte);
			if (modelJobs.contains(jte))
				ji.refresh();
			else
				changed |= ji.remove();
		}
		
		// find all added
		for (int i= 0; i < roots.length; i++) {
			JobTreeElement jte= roots[i];
			if (!shownJobs.contains(jte)) {
				new JobItem(list, jte);
				changed= true;
				lastAdded= jte;
			}
		}
			
		relayout(changed, changed);
		if (lastAdded != null)
			reveal(lastAdded);
		
		// check verbose action
		if (verboseAction != null)
			verboseAction.setChecked(ProgressViewUpdater.getSingleton().debug);
	}
	
	private void clearAll() {
		Control[] children= list.getChildren();
		boolean changed= false;
		for (int i= 0; i < children.length; i++)
			changed |= ((JobItem)children[i]).kill(false, true);
		relayout(changed, changed);
	}
	
	private JobItem findJobItem(String tag, Control[] children, JobTreeElement jte) {
		if (children == null)
			children= list.getChildren();
		for (int i= 0; i < children.length; i++) {
			JobItem ji= (JobItem) children[i];
			if (jte == ji.jobTreeElement)
				return ji;
		}
		//System.err.println("*** not found (" + tag + ") " + jte.getCondensedDisplayString());
		return null;
	}

	boolean use(JobTreeElement jte) {
		Object parent= jte.getParent();
		if (parent != null) {
			refreshJobItems(new Object[]{ parent } );
			//System.err.println("---use: " + jte.getCondensedDisplayString());
			return false;
		}
		return true;
	}
	
	public void setFocus() {
		if (list != null && !list.isDisposed())
			list.setFocus();
	}
	
	private Image getImage(Display display, String name) {
		ImageDescriptor id= ImageDescriptor.createFromFile(JobView.class, name);
		if (id != null)
			return id.createImage(display);
		return null;
	}
	
	private void reveal(JobTreeElement jte) {
		JobItem ji= findJobItem("reveal", null, jte);
		if (ji != null) {
			Rectangle bounds= ji.getBounds();
			/*
			Rectangle visArea= scroller.getClientArea();
			Point o= scroller.getOrigin();
			visArea.x= o.x;
			visArea.y= o.y;
			*/
			scroller.setOrigin(0, bounds.y);
		}
	}

	/*
	 * Marks the given JobItem as selected.
	 */
	private void select(JobItem c) {
		Control[] cs= list.getChildren();
		for (int i= 0; i < cs.length; i++) {
			JobItem ji= (JobItem) cs[i];
			if (ji == c) {
				ji.fSelected= !ji.fSelected;
			} else {
				ji.fSelected= false;	
			}
		}		
		relayout(false, true);
	}
	
	/*
	 * Needs to be called after items have been added or removed,
	 * or after the size of an item has changed.
	 * Updates the background of all items.
	 * Ensures that the background following the last item is always white.
	 */
	private void relayout(boolean layout, boolean refreshBackgrounds) {
		if (layout) {
			Point size= list.computeSize(list.getClientArea().x, SWT.DEFAULT);
			list.setSize(size);
			scroller.setMinSize(size);	
		}
		
		if (refreshBackgrounds) {
			Control[] children= list.getChildren();
			boolean dark= (children.length % 2) == 1;
			for (int i= 0; i < children.length; i++) {
				JobItem ji= (JobItem) children[i];
				ji.updateBackground(dark);
				dark= !dark;
			}			
		}
	}
	
	public JobTreeElement[] getDisplayedValues(JobTreeElement[] elements) {
		HashSet showing = new HashSet();
		
		for (int i = 0; i < elements.length; i++) {
			JobTreeElement element = (JobTreeElement) elements[i];
			if (element.isActive()) {
				if (element.isJobInfo() && ((JobInfo)element).getJob().getState() != Job.RUNNING)
					continue;
				showing.add(element);
			}
		}
		
		return (JobTreeElement[]) showing.toArray(new JobTreeElement[showing.size()]);
	}

	private Object[] getRoots(Object[] elements, boolean subWithParent) {
		if (elements.length == 0)
			return elements;
		HashSet roots = new HashSet();
		for (int i = 0; i < elements.length; i++) {
			JobTreeElement element = (JobTreeElement) elements[i];
			if (element.isJobInfo()) {
				GroupInfo group = ((JobInfo) element).getGroupInfo();
				if (group == null)
					roots.add(element);
				else {
					if (subWithParent)
						roots.add(group);
				}
			} else
				roots.add(element);
		}
		return roots.toArray();
	}
}
