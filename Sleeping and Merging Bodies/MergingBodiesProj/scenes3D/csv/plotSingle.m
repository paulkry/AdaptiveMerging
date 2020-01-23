clear 
close all
scene_name = "funnel1_merged"
plot_name = "Performance With Time for " + scene_name + " Scene"
X_merged = readtable(scene_name + ".csv")

num_timesteps = min(height(X_merged))

hfig = figure('Renderer', 'painters', 'Position', [10 10 600 400]), set(gcf,'color','w'); hold on;



subplot(2,1,1);
hold on
plot(X_merged{1:num_timesteps, 20 }, 'DisplayName','Full LCP Solve')

plot(X_merged{1:num_timesteps, 3 }, 'DisplayName','Contact Detection')

plot(X_merged{1:num_timesteps, 6 }, 'DisplayName','Single Iteration PGS')

plot(X_merged{1:num_timesteps, 9 }, 'DisplayName','Merging')

plot(X_merged{1:num_timesteps, 15 }, 'DisplayName','Unmerging')
set(gca,'yscale','log')

hold off
legend
ylabel("Time (s)")



subplot(2,1,2);

plot(X_merged{1:num_timesteps, 1}, 'DisplayName','# Bodies')
hold
plot(X_merged{1:num_timesteps, 2}, 'DisplayName','# Contacts')

legend
set(gca,'yscale','log')



hold off

fontSize = 10;
fontName = 'Times New Roman';

set(gca, 'fontsize', fontSize);
set(hfig,'Units','Inches');
pos = get(hfig,'Position');
set(hfig,'PaperPositionMode','Auto','PaperUnits','Inches','PaperSize',[pos(3), pos(4)])
print(hfig,scene_name,'-dpdf','-r0')