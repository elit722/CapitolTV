# Capitol TV — régie multi-flux pour les Jeux

Site de react « Capitol TV » pour l'event Hunger Games RP. Il tourne entièrement sur **Cloudflare Workers**, avec une offre gratuite qui suffit pour un event :

- **Connexion Discord obligatoire.** Personne n'accède au site sans compte Discord, et tu peux le réserver aux membres d'un serveur.
- **Temps réel.** Annonces, alertes, présence et équipe passent par un *Durable Object* et des WebSockets.
- **Différé configurable.** La régie règle le délai, et les annonces du site restent synchronisées avec les streams retardés.
- **Pages Tributs et Sponsors**, avec fermeture des flux par horaires pour les joueurs encore en jeu.
- **Présence.** Le compteur « en ligne » est affiché en permanence, et on voit qui regarde quel tribut.
- **Page Équipe** avec trois niveaux d'accès : Régie, Modération, Équipe.

```
capitol-tv/
├── wrangler.jsonc     ← configuration Cloudflare (à remplir)
├── package.json
├── src/worker.js      ← serveur : connexion Discord, temps réel, Twitch
└── public/
    ├── index.html     ← le site
    ├── login.html     ← page de connexion
    └── logo.png       ← ton logo (à ajouter)
```

---

## 1. Préparer

1. Installe **Node.js 20 ou plus** (nodejs.org).
2. Ouvre un terminal dans le dossier `capitol-tv`, puis lance :
   ```
   npm install
   npx wrangler login
   ```
   Le terminal ouvre ton navigateur pour te connecter à ton compte Cloudflare. Crée-en un, gratuitement, si besoin.
3. Place ton image de logo dans `public/logo.png`.

## 2. Créer l'application Discord

1. Va sur **discord.com/developers/applications**, clique sur *New Application* et nomme-la « Capitol TV ».
2. Dans l'onglet **OAuth2** :
   - Copie le **Client ID**.
   - Clique sur *Reset Secret* et copie le **Client Secret**. Garde-le pour toi.
   - Dans **Redirects**, ajoute l'adresse de rappel de ton site. La première fois, ce sera :
     `https://capitol-tv.<ton-sous-domaine>.workers.dev/auth/callback`
     Tu connaîtras l'adresse exacte après le premier déploiement (étape 4). Tu pourras revenir l'ajouter à ce moment-là.

## 3. Remplir la configuration

Dans `wrangler.jsonc`, section `vars` :

| Variable | Valeur |
|---|---|
| `DISCORD_CLIENT_ID` | le Client ID Discord |
| `OWNER_DISCORD_IDS` | **ton ID Discord** (Discord › Paramètres › Avancés › Mode développeur, puis clic droit sur ton profil › *Copier l'identifiant*). Pour plusieurs organisateurs, sépare les ID par des virgules. Ces comptes sont toujours en régie. |
| `DISCORD_GUILD_ID` | *facultatif* : ID du serveur Discord de l'event. Si tu le remplis, seuls ses membres peuvent entrer. |
| `TWITCH_CLIENT_ID` | *facultatif*, voir plus bas |

Ensuite, enregistre les secrets. Chaque commande te demande de coller la valeur :

```
npx wrangler secret put DISCORD_CLIENT_SECRET
npx wrangler secret put SESSION_SECRET
```

Pour `SESSION_SECRET`, colle une longue phrase aléatoire, d'au moins 32 caractères. Elle sert à signer les sessions.

## 4. Mettre en ligne

```
npx wrangler deploy
```

Wrangler affiche l'adresse du site. Ajoute `https://…workers.dev/auth/callback` dans les *Redirects* Discord (étape 2), puis ouvre le site. Tu arrives sur la page de connexion Discord. Une fois connecté avec ton compte, tu es en **Régie**.

Pour mettre à jour le site plus tard, refais simplement `npx wrangler deploy`.

> **Nom de domaine perso**, par exemple `capitol.tondomaine.fr` : dans le tableau de bord Cloudflare, va dans Workers › capitol-tv › *Settings › Domains & Routes*. Pense à ajouter aussi la nouvelle adresse de rappel dans Discord.

---

## Le différé de diffusion

Tu le règles dans **Régie › Jeux & différé**, de 30 s à 15 min.

**Ce que fait le site :**
- Pour les spectateurs, il retient tout ce qu'il annonce pendant le délai choisi : coups de canon, parachutes, flashs, hommages, journal, statuts « blessé / tombé », classement. Chaque annonce apparaît au moment où la scène passe dans les streams.
- Le serveur garde ces informations jusqu'à ce que le délai soit écoulé. Elles n'arrivent jamais en avance dans le navigateur des spectateurs.
- Le chat Twitch peut être masqué pendant le différé, pour éviter les spoils. C'est une option.

**Ce que le site ne peut pas faire :** retarder une vidéo Twitch. **Chaque streamer doit régler le même délai** de son côté :
- sur Twitch : *Gestionnaire de stream › Délai du stream* (jusqu'à 15 min, réservé aux partenaires) ;
- ou dans OBS : *Paramètres › Avancé › Délai de diffusion* (disponible pour tout le monde).

**Mode présentateur :** clique sur ton avatar, en haut à droite. Tu vois alors l'antenne avec le différé, comme les spectateurs. C'est idéal pour réagir en live sans te spoiler. Les alertes restent visibles, mais l'édition est mise en pause.

## Les tributs

- **Page Tributs** (en haut, ou touche `U`) : tous les participants, avec leur fiche RP et leur statut **En vie / Blessé / Disparu / Décédé**. On peut filtrer par statut et par district, et chercher par nom. Un clic sur un tribut ouvre sa fiche complète, avec les boutons Regarder, Signaler et Sponsoriser.
- **Ajouter des tributs** : *Régie › Tributs*. Tu peux faire un ajout rapide ou un import en masse : une ligne par tribut, au format `pseudo ; nom ; district ; n° ; alliance ; ID Discord`. Le bouton **Fiche** de chaque ligne permet ensuite de remplir :
  - les infos RP : âge, arme ou atout, compétences, histoire, portrait (lien https vers une image) ;
  - le compte Discord du joueur ;
  - un lien sponsor dédié.
- **Changer un statut** pendant le live : boutons rapides sous chaque flux (Blessé, Disparu, Tombé), ou dans la fiche. Avec le différé, les spectateurs voient chaque changement de statut seulement quand la scène passe dans les streams.

## Fermer les flux aux tributs en jeu

Ce réglage se trouve dans *Régie › Accès des tributs*.

1. Lie chaque tribut au **compte Discord de son joueur**, dans sa fiche. Si le joueur est connecté au site, son compte apparaît dans la liste proposée.
2. Choisis un mode :
   - **Selon les créneaux** : par exemple tous les jours de 21:00 à 02:00. Un créneau peut passer minuit.
   - **Fermé maintenant** : fermeture manuelle immédiate.
3. Pendant un créneau, les joueurs dont le tribut est encore en jeu (en vie, blessé ou disparu) voient l'écran « Retransmission fermée aux tributs » avec l'heure de réouverture.
   - Le serveur ne leur envoie plus aucun pseudo Twitch, ni statut live, ni compteur de spectateurs.
   - Ils gardent l'accès aux pages Tributs, Sponsors et Équipe.
   - Un tribut **décédé** retrouve l'accès automatiquement.
   - La régie et la modération ne sont jamais bloquées.

> Limite : Capitol TV ferme ses propres lecteurs, mais ne peut pas empêcher un joueur d'aller directement sur twitch.tv. Ça relève du règlement de l'event.

## Sponsors

Les dons ne passent **pas** par Capitol TV. Dans *Régie › Sponsors*, tu règles :
- le **lien de la plateforme de dons** de l'association et le texte du bouton. Le lien peut contenir `{login}`, `{name}`, `{num}` ou `{district}`, remplacés par les infos du tribut choisi ;
- un texte de présentation ;
- les **partenaires officiels** (nom, lien, logo).

La page **Sponsors** affiche ensuite un bouton « Sponsoriser » pour chaque tribut encore en lice, ainsi que les derniers parachutes annoncés. Les parachutes restent de simples annonces à l'antenne : aucun montant n'est géré par le site.

## Cagnotte pour l'association

Dans *Régie › Cagnotte* :
- règle l'**objectif** et la devise ;
- **ajoute ou retire** des montants (boutons rapides +5, +10, +20, +50, +100, +500, ou montant libre avec une note).

Chaque mouvement est enregistré avec le nom de la personne et peut être annulé. Le total est calculé par le serveur.

Le public voit la barre de progression dans l'en-tête, le bandeau et la page Sponsors, avec une petite animation « +50 € ! » à chaque ajout. Les dons eux-mêmes se font sur la **page de dons dédiée** (lien réglé dans *Régie › Sponsors*), puis sont reversés à l'association.

## Consultation sans compte

Dans *Régie › Accès & bannis*, le bouton **Ouvrir à tout le monde** permet à n'importe qui de regarder Capitol TV sans compte Discord. Les invités :
- peuvent regarder, zapper et voir les pages Tributs, Sponsors et Équipe ;
- ne peuvent pas envoyer de signalement : un bouton « Se connecter » leur est proposé ;
- sont comptés dans « en ligne », sans être listés un par un.

**Rendre la connexion obligatoire** renvoie immédiatement les invités vers la page de connexion.

## Caméras en jeu : le mod Minecraft (Fabric 1.21.8)

Le dossier `mod/` contient un mod Fabric pour les caméramans. Le fichier `.jar` est compilé automatiquement par GitHub Actions (onglet **Actions › Mod caméra › Artifacts**). Il faut aussi **Fabric API** dans le dossier `mods`.

**Mise en place**
1. Page **Équipe** › modifier un membre › **Caméra en jeu** : *Caméraman* ou *Responsable caméra*.
2. Chaque caméra ouvre **Mon compte › Mod caméra › Générer mon jeton** sur le site, puis en jeu : touche **K** › **Réglages** › **Coller** › **Connecter**.
3. Dans la fiche de chaque tribut, renseigner son **pseudo Minecraft** (sinon il n'apparaît ni sur la carte ni dans les actes).

**Dans le jeu (touche K)**
- **Actions** : les signalements en cours, avec « écran dans 1:42 » (le différé) ; boutons TP, *Je filme*, *Je prends*, *Traité*.
- **Annonces** : les actes annonciateurs — attaques, dégâts **avec la cause**, morts, effets, totems, points de vie bas, rencontres entre tributs, annonces spéciales — filtrables par type et par tribut.
- **Carte** : positions des tributs et des caméras, zoom à la molette, déplacement à la souris, **TP en un clic** (vers un tribut, une caméra ou un point).
- **Signaler** : tribut, type, urgent, « je filme cette action », détails. La touche **J** envoie un signal rapide sur le tribut suivi (ou le plus proche).
- **Comms** : Général, Caméras, messages directs, réponses rapides ; « Buzz » pour la régie et le responsable caméra.
- **Équipe** : état de chaque caméra (actif, en rec, pause, inactif), qui suit qui, TP vers une caméra ; le responsable peut assigner une caméra à un tribut.
- **Demandes** : demandes de couverture (prendre, terminer, libérer).
- **Réglages** : état, notifications (toutes / supérieurs seulement / aucune), sons, bulles, encart HUD et sa position, envoi de ce que je vois.

**Téléportation** : en mode spectateur, le TP vers un joueur passe par la téléportation vanilla (aucun droit requis). Sinon, le mod serveur s'en charge (spectateurs, opérateurs ou pseudos listés dans `camNames`). Sans mod serveur, le mod envoie `/tp` (droits requis).

**Mod côté serveur (recommandé)** : le même `.jar` installé sur le serveur de l'event envoie la position de *tous* les tributs, les dégâts avec leur cause, les morts et les effets. Régie › **Carte & caméras** › *Afficher et copier* la clé, à coller dans `config/capitoltv-server.json` (`"key"`), puis `/capitol reload`. Commandes : `/capitol status`, `/capitol annonce <texte>` (annonce spéciale envoyée à toutes les caméras). Sans mod serveur, chaque caméra remonte ce qu'elle voit autour d'elle (les doublons sont fusionnés).

**Sur le site** (équipe uniquement) : onglets **Comms** et **Terrain** (caméras, demandes, actes, annonce spéciale) dans le panneau latéral, et compte à rebours d'antenne sur chaque alerte.

**Carte du site** : onglet **Carte** pour l'équipe (positions en direct). Régie › **Carte & caméras** › *Ouvrir la carte* puis *Rendre publique* / *Verrouiller : équipe seulement*. Le public voit les positions **avec le différé**, et les comptes des tributs encore en vie n'y ont jamais accès.

## L'équipe et les accès

- **Page Équipe** : onglet « Équipe » en haut, ou touche `E`. Elle est visible par tout le monde.
- **Ajouter quelqu'un** : ouvre l'onglet **En ligne**, survole la personne et clique sur **+**. Tu peux aussi l'ajouter depuis la page Équipe avec son ID Discord.
- **Niveaux d'accès :**
  - **Régie** : tout gérer.
  - **Modération** : alertes et bannissements.
  - **Équipe** : affichage sur la page Équipe, sans accès à la régie.
- **Bannir** quelqu'un qui abuse des signalements : bouton ⊘, sur l'alerte ou dans l'onglet En ligne. Pour rendre l'accès : *Régie › Accès & bannis*.

## Options

**Statut en direct Twitch** (qui est en live, nombre de spectateurs) :
1. Sur **dev.twitch.tv/console**, enregistre une application (catégorie *Website Integration*, type *Confidentiel*).
2. Mets son **Client ID** dans `TWITCH_CLIENT_ID`, puis lance `npx wrangler secret put TWITCH_CLIENT_SECRET`.
3. Redéploie. Le serveur interroge Twitch chaque minute.

**Overlay OBS** (bandeau et annonces sur fond transparent) :
1. Lance `npx wrangler secret put OVERLAY_KEY` et choisis un mot de passe.
2. Dans OBS, ajoute une source *Navigateur* en 1920×1080 avec l'adresse `https://…/overlay/ton-mot-de-passe`.

## Tester en local

```
npx wrangler dev
```

Pour que la connexion Discord marche en local, ajoute aussi `http://localhost:8787/auth/callback` dans les *Redirects* Discord. Les secrets locaux se mettent dans un fichier `.dev.vars` :

```
DISCORD_CLIENT_SECRET=...
SESSION_SECRET=...
```

## Coûts et limites

- Tout tient dans l'offre gratuite de Cloudflare pour un event de quelques jours. Au-delà d'environ 100 000 requêtes par jour, le forfait payant coûte 5 $/mois.
- Le compteur « en ligne » affiche tout le monde. La liste détaillée est limitée aux 600 premières personnes, pour rester fluide même avec beaucoup de monde.
